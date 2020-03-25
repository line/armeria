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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.internal.common.KeepAliveHandler.State;
import com.linecorp.armeria.testing.junit.common.EventLoopExtension;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

class KeepAliveHandlerTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;

    @BeforeEach
    void setUp() {
        channel = spy(new EmbeddedChannel());
        when(channel.eventLoop()).thenReturn(eventLoop.get());
        ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
    }

    @AfterEach
    void tearDown() {
        channel.finish();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void checkReadOrWrite(boolean hasRequests) throws InterruptedException {
        final long idleTimeout = 10000;
        final long pingInterval = 0;
        final ChannelFuture channelFuture = channel.newPromise();
        final KeepAliveHandler keepAliveHandler =
                new KeepAliveHandler(channel, "test", idleTimeout, pingInterval) {
                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        return channelFuture;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return hasRequests;
                    }
                };

        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        keepAliveHandler.initialize(ctx);
        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        keepAliveHandler.onReadOrWrite();
        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        Thread.sleep(idleTimeout / 2);
        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        Thread.sleep(idleTimeout);
        if (hasRequests) {
            assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);
        } else {
            assertThat(keepAliveHandler.state()).isEqualTo(State.SHUTDOWN);
        }
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void checkPing(boolean hasRequests) throws InterruptedException {
        final long idleTimeout = 10000;
        final long pingInterval = 1000;
        final ChannelPromise promise = channel.newPromise();
        final KeepAliveHandler keepAliveHandler =
                new KeepAliveHandler(channel, "test", idleTimeout, pingInterval) {
                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        return promise;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return hasRequests;
                    }
                };

        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        keepAliveHandler.initialize(ctx);
        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        keepAliveHandler.onReadOrWrite();
        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        keepAliveHandler.writePing(ctx);
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(State.PING_SCHEDULED));

        promise.setSuccess();
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(State.PENDING_PING_ACK));

        keepAliveHandler.onPing();
        assertThat(keepAliveHandler.state()).isEqualTo(State.IDLE);

        Thread.sleep(pingInterval * 2);
        assertThat(keepAliveHandler.state()).isEqualTo(State.PENDING_PING_ACK);
    }
}
