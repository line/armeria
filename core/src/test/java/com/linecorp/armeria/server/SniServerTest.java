/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import javax.net.ssl.SSLContext;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.InMemoryDnsResolver;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Test;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NetUtil;

public class SniServerTest extends AbstractServerTest {

    private static SelfSignedCertificate sscA;
    private static SelfSignedCertificate sscB;
    private static SelfSignedCertificate sscC;

    private static InMemoryDnsResolver dnsResolver;

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        dnsResolver = new InMemoryDnsResolver();
        dnsResolver.add("a.com", NetUtil.LOCALHOST4);
        dnsResolver.add("b.com", NetUtil.LOCALHOST4);
        dnsResolver.add("c.com", NetUtil.LOCALHOST4);
        dnsResolver.add("mismatch.com", NetUtil.LOCALHOST4);
        dnsResolver.add("127.0.0.1", NetUtil.LOCALHOST4);

        sscA = new SelfSignedCertificate("a.com");
        sscB = new SelfSignedCertificate("b.com");
        sscC = new SelfSignedCertificate("c.com");

        final VirtualHostBuilder a = new VirtualHostBuilder("a.com");
        final VirtualHostBuilder b = new VirtualHostBuilder("b.com");
        final VirtualHostBuilder c = new VirtualHostBuilder("c.com");

        a.serviceAt("/", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "a.com");
            }
        });

        b.serviceAt("/", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "b.com");
            }
        });

        c.serviceAt("/", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "c.com");
            }
        });

        a.sslContext(SessionProtocol.HTTPS, sscA.certificate(), sscA.privateKey());
        b.sslContext(SessionProtocol.HTTPS, sscB.certificate(), sscB.privateKey());
        c.sslContext(SessionProtocol.HTTPS, sscC.certificate(), sscC.privateKey());

        sb.virtualHost(a.build());
        sb.virtualHost(b.build());
        sb.defaultVirtualHost(c.build());

        sb.port(0, SessionProtocol.HTTPS);
    }

    @AfterClass
    public static void deleteSelfSignedCertificated() {
        sscA.delete();
        sscB.delete();
        sscC.delete();
    }

    @Test
    public void testSniMatch() throws Exception {
        try (CloseableHttpClient hc = newHttpClient()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://a.com:" + httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("a.com"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://b.com:" + httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("b.com"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://c.com:" + httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("c.com"));
            }
        }
    }

    @Test
    public void testSniMismatch() throws Exception {
        try (CloseableHttpClient hc = newHttpClient()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet("https://mismatch.com:" + httpsPort()))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("c.com"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(httpsUri("/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("c.com"));
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
}
