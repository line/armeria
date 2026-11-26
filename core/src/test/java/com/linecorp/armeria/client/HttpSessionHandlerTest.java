/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.armeria.client.Http2ClientSettingsTest.readBytes;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.util.concurrent.Promise;

class HttpSessionHandlerTest {

    @Test
    void proxySessionTimesOutWithoutProxyConnectionEvent() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.freezeTime();
        try {
            final Promise<Channel> sessionPromise = addProxySessionHandler(channel);
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();

            assertThat(sessionPromise.cause()).isInstanceOf(UnprocessedRequestException.class)
                                             .hasCauseInstanceOf(ProxyConnectException.class);
            assertThat(channel.isOpen()).isFalse();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void proxyConnectionEventRestartsSessionTimeout() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.freezeTime();
        try {
            final Promise<Channel> sessionPromise = addProxySessionHandler(channel);
            channel.advanceTimeBy(750, TimeUnit.MILLISECONDS);
            channel.pipeline().fireUserEventTriggered(proxyConnectionEvent());

            channel.advanceTimeBy(500, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sessionPromise.isDone()).isFalse();
            assertThat(channel.isOpen()).isTrue();

            channel.advanceTimeBy(500, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sessionPromise.cause()).isInstanceOf(SessionProtocolNegotiationException.class);
            assertThat(channel.isOpen()).isFalse();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void proxyConnectionEventDoesNotScheduleTimeoutForCompletedSession(boolean success) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.freezeTime();
        try {
            final Promise<Channel> sessionPromise = addProxySessionHandler(channel);
            if (success) {
                sessionPromise.setSuccess(channel);
            } else {
                sessionPromise.setFailure(new IllegalStateException("session failed"));
            }
            channel.pipeline().fireUserEventTriggered(proxyConnectionEvent());

            assertThat(channel.runScheduledPendingTasks()).isEqualTo(-1);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static Promise<Channel> addProxySessionHandler(EmbeddedChannel channel) {
        final HttpClientFactory factory = (HttpClientFactory) ClientFactory.ofDefault().unwrap();
        final HttpChannelPool pool =
                new HttpChannelPool(factory, channel.eventLoop(), factory.sslContextFactory());
        final Promise<Channel> sessionPromise = channel.eventLoop().newPromise();
        final PoolKey poolKey = new PoolKey(Endpoint.of("127.0.0.1", 443),
                                            ProxyConfig.connect(new InetSocketAddress("127.0.0.1", 8080)),
                                            null, null);
        channel.pipeline().addLast(new HttpSessionHandler(
                pool, channel, sessionPromise, 1000,
                SessionProtocol.H1, SerializationFormat.NONE, poolKey, factory));
        return sessionPromise;
    }

    private static ProxyConnectionEvent proxyConnectionEvent() {
        return new ProxyConnectionEvent("http", "none", new InetSocketAddress("127.0.0.1", 8080),
                                         new InetSocketAddress("127.0.0.1", 443));
    }

    @Test
    void disconnectedConnectProxyDoesNotLeaveSessionPending() throws Exception {
        final AtomicInteger failedAttempts = new AtomicInteger();
        try (ServerSocket proxy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(5000);
            final ProxyConfigSelector selector = new ProxyConfigSelector() {
                @Override
                public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                    return ProxyConfig.connect(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()));
                }

                @Override
                public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                          SocketAddress proxyAddress, Throwable cause) {
                    assertThat(cause).isInstanceOf(UnprocessedRequestException.class)
                                     .hasCauseInstanceOf(ProxyConnectException.class);
                    failedAttempts.incrementAndGet();
                }
            };
            try (ClientFactory factory = ClientFactory.builder()
                                                       .proxyConfig(selector)
                                                       .connectTimeoutMillis(1000)
                                                       .tlsNoVerify()
                                                       .build()) {
                final WebClient client = WebClient.builder("https://127.0.0.1:443")
                                                  .factory(factory)
                                                  .responseTimeoutMillis(0)
                                                  .build();
                final CompletableFuture<AggregatedHttpResponse> response = client.get("/").aggregate();
                try (Socket socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    final BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    assertThat(reader.readLine()).isEqualTo("CONNECT 127.0.0.1:443 HTTP/1.1");
                    String line;
                    do {
                        line = reader.readLine();
                        assertThat(line).isNotNull();
                    } while (!line.isEmpty());
                }

                assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(ProxyConnectException.class);
                assertThat(failedAttempts).hasValue(1);
            }
        }
    }

    @Test
    void connectionTimeoutBeforeSettingsFrameIsSent() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .idleTimeoutMillis(1000)
                                  .useHttp2Preface(true)
                                  .build()) {
            final int port = ss.getLocalPort();
            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();

            try (Socket s = ss.accept()) {
                final InputStream in = s.getInputStream();
                // Read the connection preface and discard it.
                readBytes(in, connectionPrefaceBuf().readableBytes());
                // Read a SETTINGS frame.
                readBytes(in, 21);
                // Do not send back the SETTINGS frame.
                TimeUnit.SECONDS.sleep(3);
                assertThat(future.isCompletedExceptionally()).isTrue();
                assertThatThrownBy(future::join).hasRootCauseExactlyInstanceOf(ClosedSessionException.class);
            }
        }
    }
}
