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

package com.linecorp.armeria.client;

import static org.junit.Assert.assertEquals;

import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.testing.internal.MockAddressResolverGroup;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;

public class HttpClientSniTest {

    private static final Server server;

    private static int httpsPort;
    private static ClientFactory clientFactory;
    private static final SelfSignedCertificate sscA;
    private static final SelfSignedCertificate sscB;

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sscA = new SelfSignedCertificate("a.com");
            sscB = new SelfSignedCertificate("b.com");

            final VirtualHostBuilder a = new VirtualHostBuilder("a.com");
            final VirtualHostBuilder b = new VirtualHostBuilder("b.com");

            a.service("/", new SniTestService("a.com"));
            b.service("/", new SniTestService("b.com"));

            a.tls(sscA.certificate(), sscA.privateKey());
            b.tls(sscB.certificate(), sscB.privateKey());

            sb.virtualHost(a.build());
            sb.defaultVirtualHost(b.build());
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();
        httpsPort = server.activePorts().values().stream()
                          .filter(ServerPort::hasHttps).findAny().get().localAddress()
                          .getPort();
        clientFactory = new ClientFactoryBuilder()
                .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
                .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                .build();
    }

    @AfterClass
    public static void destroy() throws Exception {
        CompletableFuture.runAsync(() -> {
            clientFactory.close();
            server.stop();
            sscA.delete();
            sscB.delete();
        });
    }

    @Test
    public void testMatch() throws Exception {
        testMatch("a.com");
        testMatch("b.com");
    }

    private static void testMatch(String fqdn) throws Exception {
        assertEquals(fqdn + ": CN=" + fqdn, get(fqdn));
    }

    @Test
    public void testMismatch() throws Exception {
        testMismatch("127.0.0.1");
        testMismatch("mismatch.com");
    }

    private static void testMismatch(String fqdn) throws Exception {
        assertEquals("b.com: CN=b.com", get(fqdn));
    }

    private static String get(String fqdn) throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, "https://" + fqdn + ':' + httpsPort);

        final AggregatedHttpMessage response = client.get("/").aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        return response.contentUtf8();
    }

    @Test
    public void testCustomAuthority() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, "https://127.0.0.1:" + httpsPort);
        final AggregatedHttpMessage response =
                client.execute(HttpHeaders.of(HttpMethod.GET, "/")
                                          .set(HttpHeaderNames.AUTHORITY, "a.com:" + httpsPort))
                      .aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("a.com: CN=a.com", response.contentUtf8());
    }

    @Test
    public void testCustomAuthorityWithAdditionalHeaders() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, "https://127.0.0.1:" + httpsPort);
        try (SafeCloseable unused = Clients.withHttpHeader(HttpHeaderNames.AUTHORITY, "a.com:" + httpsPort)) {
            final AggregatedHttpMessage response = client.get("/").aggregate().get();
            assertEquals(HttpStatus.OK, response.status());
            assertEquals("a.com: CN=a.com", response.contentUtf8());
        }
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
