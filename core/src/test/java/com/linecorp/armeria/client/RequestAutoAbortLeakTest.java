/*
 * Copyright 2025 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.util.AttributeMap;

class RequestAutoAbortLeakTest {

    private static final AtomicReference<Http2Stream> streamRef = new AtomicReference<>();
    private static final String AUTO_ABORT_MILLIS_HEADER = "abort-delay-millis";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/http2", (ctx, req) -> {
                final int abortDelayMillis = Integer.parseInt(req.headers().get(AUTO_ABORT_MILLIS_HEADER));
                ctx.setRequestAutoAbortDelayMillis(abortDelayMillis);
                final Channel channel = ctx.log().ensureAvailable(RequestLogProperty.SESSION).channel();
                final Http2Connection connection =
                        channel.pipeline().get(Http2ConnectionHandler.class).connection();
                final Http2Stream stream = connection.stream(connection.remote().lastStreamCreated());
                streamRef.set(stream);
                return HttpResponse.of(200);
            });

            sb.service("/http1", (ctx, req) -> {
                final int abortDelayMillis = Integer.parseInt(req.headers().get(AUTO_ABORT_MILLIS_HEADER));
                ctx.setRequestAutoAbortDelayMillis(abortDelayMillis);
                return HttpResponse.of(200);
            });
        }
    };

    @BeforeEach
    void beforeEach() {
        streamRef.set(null);
    }

    public static Stream<Arguments> autoAbortArgs() {
        return Stream.of(
                Arguments.of(0, ResponseCompleteException.class),
                // 10 seconds should be enough for the reset to abort the server request
                Arguments.of(10_000, ClosedStreamException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("autoAbortArgs")
    void http1Test(int abortDelayMillisHeader, Class<? extends Exception> requestCauseClass) throws Exception {
        final CountingConnectionPoolListener listener = new CountingConnectionPoolListener();
        try (ClientFactory cf = ClientFactory.builder()
                                             .connectionPoolListener(listener)
                                             .idleTimeoutMillis(0)
                                             .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H1C))
                                              .factory(cf).build();
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/http1",
                                                             AUTO_ABORT_MILLIS_HEADER, abortDelayMillisHeader);
            final HttpRequestWriter writer = HttpRequest.streaming(headers);
            final HttpResponse res;
            final ClientRequestContext cctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                res = client.execute(writer);
                cctx = captor.get();
            }

            final AggregatedHttpResponse aggRes = res.aggregate().join();
            assertThat(aggRes.status().code()).isEqualTo(200);

            assertContextCompleted(cctx, ResponseCompleteException.class);

            final ServiceRequestContext sctx = server.requestContextCaptor().poll();
            sctx.request().subscribe();
            assertContextCompleted(sctx, requestCauseClass);

            await().untilAsserted(() -> assertThat(listener.connectionCount).hasValue(0));
        }
    }

    @ParameterizedTest
    @MethodSource("autoAbortArgs")
    void http2Test(int abortDelayMillisHeader, Class<? extends Exception> requestCauseClass) throws Exception {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H2C));
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/http2",
                                                         AUTO_ABORT_MILLIS_HEADER, abortDelayMillisHeader);
        final HttpRequestWriter writer = HttpRequest.streaming(headers);
        final HttpResponse res;
        final ClientRequestContext cctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            res = client.execute(writer);
            cctx = captor.get();
        }
        await().untilAsserted(() -> assertThat(streamRef.get()).isNotNull());
        final Http2Stream stream = streamRef.get();

        final AggregatedHttpResponse aggRes = res.aggregate().join();
        assertThat(aggRes.status().code()).isEqualTo(200);

        assertContextCompleted(cctx, ResponseCompleteException.class);

        final ServiceRequestContext sctx = server.requestContextCaptor().poll();
        sctx.request().subscribe();
        assertContextCompleted(sctx, requestCauseClass);

        await().untilAsserted(() -> assertThat(stream.state()).isEqualTo(State.CLOSED));
    }

    private static void assertContextCompleted(RequestContext ctx,
                                               Class<? extends Exception> requestCauseClass) {
        assertThatThrownBy(() -> ctx.request().whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(requestCauseClass);
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestCause()).isNull();
        assertThat(log.responseCause()).isNull();
    }

    private static class CountingConnectionPoolListener extends ConnectionPoolListenerAdapter {

        private final AtomicLong connectionCount = new AtomicLong();

        @Override
        public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                   InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            connectionCount.incrementAndGet();
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            connectionCount.decrementAndGet();
        }
    }
}
