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

import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static org.junit.Assert.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.VirtualHostBuilder;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

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

            sb.port(0, HTTPS);

            final VirtualHostBuilder a = new VirtualHostBuilder("a.com");
            final VirtualHostBuilder b = new VirtualHostBuilder("b.com");

            a.service("/", new SniTestService("a.com"));
            b.service("/", new SniTestService("b.com"));

            a.sslContext(HTTPS, sscA.certificate(), sscA.privateKey());
            b.sslContext(HTTPS, sscB.certificate(), sscB.privateKey());

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
                          .filter(p -> p.protocol() == HTTPS).findAny().get().localAddress()
                          .getPort();
        clientFactory = new ClientFactoryBuilder()
                .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
                .addressResolverGroupFactory(eventLoopGroup -> new DummyAddressResolverGroup())
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

        AggregatedHttpMessage response = client.get("/").aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        return response.content().toString(StandardCharsets.UTF_8);
    }

    private static class DummyAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor eventExecutor) {
            return new InetSocketAddressResolver(eventExecutor, new InetNameResolver(eventExecutor) {
                @Override
                protected void doResolve(String hostname, Promise<InetAddress> promise) {
                    try {
                        promise.setSuccess(newAddress(hostname));
                    } catch (UnknownHostException e) {
                        promise.setFailure(e);
                    }
                }

                @Override
                protected void doResolveAll(String hostname, Promise<List<InetAddress>> promise) {
                    try {
                        promise.setSuccess(Collections.singletonList(newAddress(hostname)));
                    } catch (UnknownHostException e) {
                        promise.setFailure(e);
                    }
                }

                private InetAddress newAddress(String hostname) throws UnknownHostException {
                    return InetAddress.getByAddress(hostname, new byte[] { 127, 0, 0, 1 });
                }
            });
        }
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
