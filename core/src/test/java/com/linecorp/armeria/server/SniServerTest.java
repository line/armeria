/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.InMemoryDnsResolver;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.testing.server.SelfSignedCertificateRule;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.NetUtil;

public class SniServerTest {

    @ClassRule
    public static final SelfSignedCertificateRule sscA = new SelfSignedCertificateRule("a.com");

    @ClassRule
    public static final SelfSignedCertificateRule sscB = new SelfSignedCertificateRule("b.com");

    @ClassRule
    public static final SelfSignedCertificateRule sscC = new SelfSignedCertificateRule("c.com");

    private static InMemoryDnsResolver dnsResolver;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            dnsResolver = new InMemoryDnsResolver();
            dnsResolver.add("a.com", NetUtil.LOCALHOST4);
            dnsResolver.add("b.com", NetUtil.LOCALHOST4);
            dnsResolver.add("c.com", NetUtil.LOCALHOST4);
            dnsResolver.add("mismatch.com", NetUtil.LOCALHOST4);
            dnsResolver.add("127.0.0.1", NetUtil.LOCALHOST4);

            final VirtualHostBuilder a = new VirtualHostBuilder("a.com");
            final VirtualHostBuilder b = new VirtualHostBuilder("b.com");
            final VirtualHostBuilder c = new VirtualHostBuilder("c.com");

            a.service("/", new SniTestService("a.com"));
            b.service("/", new SniTestService("b.com"));
            c.service("/", new SniTestService("c.com"));

            a.sslContext(HTTPS, sscA.certificateFile(), sscA.privateKeyFile());
            b.sslContext(HTTPS, sscB.certificateFile(), sscB.privateKeyFile());
            c.sslContext(HTTPS, sscC.certificateFile(), sscC.privateKeyFile());

            sb.virtualHost(a.build());
            sb.virtualHost(b.build());
            sb.defaultVirtualHost(c.build());

            sb.port(0, HTTPS);
        }
    };

    @Test
    public void testSniMatch() throws Exception {
        try (CloseableHttpClient hc = newHttpClient()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://a.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("a.com: CN=a.com"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://b.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("b.com: CN=b.com"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://c.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("c.com: CN=c.com"));
            }
        }
    }

    @Test
    public void testSniMismatch() throws Exception {
        try (CloseableHttpClient hc = newHttpClient()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://mismatch.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("c.com: CN=c.com"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpsUri("/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("c.com: CN=c.com"));
            }
        }
    }

    public CloseableHttpClient newHttpClient() throws Exception {
        final SSLContext sslCtx =
                new SSLContextBuilder().loadTrustMaterial(null, (chain, authType) -> true).build();

        return HttpClients.custom()
                          .setDnsResolver(dnsResolver)
                          .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                          .setSSLContext(sslCtx)
                          .build();
    }

    private static class SniTestService extends AbstractHttpService {

        private final String domainName;

        SniTestService(String domainName) {
            this.domainName = domainName;
        }

        @Override
        protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                             HttpResponseWriter res) {
            final X509Certificate c = (X509Certificate) ctx.sslSession().getLocalCertificates()[0];
            final String name = c.getSubjectX500Principal().getName();
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, domainName + ": " + name);
        }
    }
}
