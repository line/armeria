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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RemoteInvokerFactory;
import com.linecorp.armeria.client.RemoteInvokerOption;
import com.linecorp.armeria.client.RemoteInvokerOptions;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.http.HttpService;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

public class SimpleHttpClientSniTest {

    private static final Server server;

    private static int httpsPort;
    private static RemoteInvokerFactory remoteInvokerFactory;
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

            a.serviceAt("/", new HttpService(
                    (ctx, blockingTaskExecutor, promise) ->
                            ctx.resolvePromise(promise, new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                    Unpooled.copiedBuffer("a.com", StandardCharsets.UTF_8)))));

            b.serviceAt("/", new HttpService(
                    (ctx, blockingTaskExecutor, promise) ->
                            ctx.resolvePromise(promise, new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                    Unpooled.copiedBuffer("b.com", StandardCharsets.UTF_8)))));

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
        server.start().sync();
        httpsPort = server.activePorts().values().stream()
                          .filter(p -> p.protocol() == SessionProtocol.HTTPS).findAny().get().localAddress()
                          .getPort();
        remoteInvokerFactory = new RemoteInvokerFactory(RemoteInvokerOptions.of(
                RemoteInvokerOption.TRUST_MANAGER_FACTORY.newValue(InsecureTrustManagerFactory.INSTANCE),
                RemoteInvokerOption.ADDRESS_RESOLVER_GROUP.newValue(new DummyAddressResolverGroup())));
    }

    @AfterClass
    public static void destroy() throws Exception {
        remoteInvokerFactory.close();
        server.stop();
        sscA.delete();
        sscB.delete();
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
        SimpleHttpClient client = Clients.newClient(
                remoteInvokerFactory, "none+https://" + fqdn + ':' + httpsPort,
                SimpleHttpClient.class);

        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/").build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.OK, response.status());
        return new String(response.content(), StandardCharsets.UTF_8);
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
