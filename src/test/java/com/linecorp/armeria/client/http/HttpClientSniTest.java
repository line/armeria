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

package com.linecorp.armeria.client.http;

import static org.junit.Assert.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.net.MediaType;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.SessionOption;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.NetUtil;
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

            sb.port(0, SessionProtocol.HTTPS);

            final VirtualHostBuilder a = new VirtualHostBuilder("a.com");
            final VirtualHostBuilder b = new VirtualHostBuilder("b.com");

            a.serviceAt("/", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                     HttpResponseWriter res) {
                    res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "a.com");
                }
            });

            b.serviceAt("/", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                     HttpResponseWriter res) {
                    res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "b.com");
                }
            });

            a.sslContext(SessionProtocol.HTTPS, sscA.certificate(), sscA.privateKey());
            b.sslContext(SessionProtocol.HTTPS, sscB.certificate(), sscB.privateKey());

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
                          .filter(p -> p.protocol() == SessionProtocol.HTTPS).findAny().get().localAddress()
                          .getPort();
        clientFactory = new HttpClientFactory(SessionOptions.of(
                SessionOption.TRUST_MANAGER_FACTORY.newValue(InsecureTrustManagerFactory.INSTANCE),
                SessionOption.ADDRESS_RESOLVER_GROUP.newValue(new DummyAddressResolverGroup())));
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
        assertEquals(fqdn, get(fqdn));
    }

    @Test
    public void testMismatch() throws Exception {
        testMismatch("127.0.0.1");
        testMismatch("mismatch.com");
    }

    private static void testMismatch(String fqdn) throws Exception {
        assertEquals("b.com", get(fqdn));
    }

    private static String get(String fqdn) throws Exception {
        HttpClient client = Clients.newClient(
                clientFactory, "none+https://" + fqdn + ':' + httpsPort,
                HttpClient.class);

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
                    promise.setSuccess(NetUtil.LOCALHOST4);
                }

                @Override
                protected void doResolveAll(String hostname, Promise<List<InetAddress>> promise) {
                    promise.setSuccess(Collections.singletonList(NetUtil.LOCALHOST4));
                }
            });
        }
    }
}
