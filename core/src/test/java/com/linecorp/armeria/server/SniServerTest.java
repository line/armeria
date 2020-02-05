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

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.testing.junit.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.util.NetUtil;

class SniServerTest {

    @RegisterExtension
    @Order(10)
    static final SelfSignedCertificateExtension sscA = new SelfSignedCertificateExtension("a.com");

    @RegisterExtension
    @Order(10)
    static final SelfSignedCertificateExtension sscB = new SelfSignedCertificateExtension("b.com");

    @RegisterExtension
    @Order(10)
    static final SelfSignedCertificateExtension sscC = new SelfSignedCertificateExtension("c.com");

    private static InMemoryDnsResolver dnsResolver;

    @RegisterExtension
    @Order(20)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            dnsResolver = new InMemoryDnsResolver();
            dnsResolver.add("a.com", NetUtil.LOCALHOST4);
            dnsResolver.add("b.com", NetUtil.LOCALHOST4);
            dnsResolver.add("c.com", NetUtil.LOCALHOST4);
            dnsResolver.add("mismatch.com", NetUtil.LOCALHOST4);
            dnsResolver.add("127.0.0.1", NetUtil.LOCALHOST4);

            sb.virtualHost("a.com")
              .service("/", new SniTestService("a.com"))
              .tls(sscA.certificateFile(), sscA.privateKeyFile())
              .and()
              .virtualHost("b.com")
              .service("/", new SniTestService("b.com"))
              .tls(sscB.certificateFile(), sscB.privateKeyFile());
            sb.defaultVirtualHost()
              .defaultHostname("c.com")
              .service("/", new SniTestService("c.com"))
              .tls(sscC.certificateFile(), sscC.privateKeyFile());
        }
    };

    @Test
    void testSniMatch() throws Exception {
        try (CloseableHttpClient hc = newHttpClient()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://a.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo(
                        "a.com: CN=a.com");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://b.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("b.com: CN=b.com");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://c.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("c.com: CN=c.com");
            }
        }
    }

    @Test
    void testSniMismatch() throws Exception {
        try (CloseableHttpClient hc = newHttpClient()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet("https://mismatch.com:" + server.httpsPort()))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("c.com: CN=c.com");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpsUri()))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("c.com: CN=c.com");
            }
        }
    }

    CloseableHttpClient newHttpClient() throws Exception {
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
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            final X509Certificate c = (X509Certificate) ctx.sslSession().getLocalCertificates()[0];
            final String name = c.getSubjectX500Principal().getName();
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, domainName + ": " + name);
        }
    }
}
