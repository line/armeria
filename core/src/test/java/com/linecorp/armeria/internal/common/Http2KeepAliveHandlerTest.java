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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.internal.common.AbstractKeepAliveHandler.PingState;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameWriter;

class Http2KeepAliveHandlerTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private static final long idleTimeoutMillis = 5000;
    private static final long pingIntervalMillis = 1000;

    @Mock
    private Http2FrameWriter frameWriter;
    private ChannelHandlerContext ctx;
    private EmbeddedChannel channel;

    private Http2KeepAliveHandler keepAliveHandler;

    void setup(boolean keepAliveOnPing) throws Exception {
        ctx = mock(ChannelHandlerContext.class);
        channel = spy(new EmbeddedChannel());
        when(channel.eventLoop()).thenReturn(eventLoop.get());
        when(ctx.channel()).thenReturn(channel);

        keepAliveHandler = new Http2KeepAliveHandler(
                channel, frameWriter, "test", NoopMeterRegistry.get().timer(""),
                idleTimeoutMillis, pingIntervalMillis,
                /* maxConnectionAgeMillis */ 0, /* maxNumRequestsPerConnection */ 0,
                keepAliveOnPing) {
            @Override
            protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                return false;
            }
        };

        assertThat(channel.isOpen()).isTrue();
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);
    }

    @AfterEach
    void after() {
        assertThat(channel.finish()).isFalse();
    }

    @Test
    void verifyPingAck() throws Exception {
        setup(false);
        final ChannelPromise promise = channel.newPromise();
        keepAliveHandler.initialize(ctx);
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        Thread.sleep(pingIntervalMillis * 2);
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(PingState.PING_SCHEDULED));

        promise.setSuccess();
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(PingState.PENDING_PING_ACK));

        keepAliveHandler.onPingAck(keepAliveHandler.lastPingPayload() + 1);
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.PENDING_PING_ACK);

        keepAliveHandler.onPingAck(keepAliveHandler.lastPingPayload());
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);
    }

    @ParameterizedTest
    @CsvSource({
            "false, SHUTDOWN",
            "true, PING_SCHEDULED",
    })
    void shouldKeepAliveOnPing(boolean keepAliveOnPing, PingState expectedState) throws Exception {
        setup(keepAliveOnPing);
        keepAliveHandler.initialize(ctx);
        Thread.sleep(idleTimeoutMillis / 2);
        keepAliveHandler.onPing();
        Thread.sleep(idleTimeoutMillis / 2 + 1000);
        assertThat(keepAliveHandler.state()).isEqualTo(expectedState);
    }
}
