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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.linecorp.armeria.internal.Http2KeepAliveHandler.State;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.timeout.IdleStateEvent;

class Http2KeepAliveHandlerTest {

    private static final long pingTimeout = 100;
    @Mock
    private Http2FrameWriter frameWriter;
    private EmbeddedChannel ch;
    private ChannelPromise promise;

    private Http2KeepAliveHandler keepAlive;

    @BeforeEach
    public void setup() throws Exception {
        ch = new EmbeddedChannel();
        promise = ch.newPromise();
        keepAlive = new Http2KeepAliveHandler(ch, frameWriter, null, pingTimeout, false);

        ch.pipeline().addLast(new TestIdleStateHandler(keepAlive));

        assertThat(ch.isOpen()).isTrue();
    }

    @AfterEach
    public void after() {
        assertThat(ch.finish()).isFalse();
    }

    @Test
    void testOnChannelIdle_WhenPingTimesOut_ShouldCloseConnection() throws InterruptedException {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);
        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);

        promise.setSuccess();
        waitUntilPingTimeout();

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isFalse();
        assertThat(keepAlive.getState()).isEqualTo(State.SHUTDOWN);
    }

    @Test
    void testOnChannelIdle_WhenWritePingFails_ShouldCloseConnection() throws InterruptedException {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        promise.setFailure(new IOException());

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isFalse();
        assertThat(keepAlive.getState()).isEqualTo(State.SHUTDOWN);
    }

    @Test
    void testOnChannelIdle_WhenPingAckIsReceivedWithinTimeout_ShouldNotCloseConnection()
            throws Exception {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        promise.setSuccess();
        keepAlive.onPingAck(keepAlive.getLastPingPayload());

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isTrue();
        assertThat(keepAlive.getState()).isEqualTo(State.IDLE);
    }

    private void waitUntilPingTimeout() throws InterruptedException {
        Thread.sleep(pingTimeout * 3 / 2);
        ch.runPendingTasks();
    }

    public static final class TestIdleStateHandler extends ChannelInboundHandlerAdapter {
        private final Http2KeepAliveHandler keepAlive;

        TestIdleStateHandler(Http2KeepAliveHandler keepAlive) {
            this.keepAlive = keepAlive;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            keepAlive.onChannelIdle(ctx, (IdleStateEvent) evt);
        }
    }
}
