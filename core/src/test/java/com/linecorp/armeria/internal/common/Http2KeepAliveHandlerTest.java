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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;

import com.linecorp.armeria.internal.common.Http2KeepAliveHandler.State;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.timeout.IdleStateEvent;

class Http2KeepAliveHandlerTest {

    private static final String sendPingsOnNoActiveStreams = "sendPingsOnNoActiveStreams";

    private static final long pingTimeoutMillis = 100;
    @Mock
    private Http2FrameWriter frameWriter;
    @Mock
    private Http2Connection connection;
    private EmbeddedChannel ch;
    private ChannelPromise promise;

    private Http2KeepAliveHandler keepAlive;

    @BeforeEach
    public void setup(TestInfo testInfo) throws Exception {
        ch = new EmbeddedChannel();
        promise = ch.newPromise();
        keepAlive = new Http2KeepAliveHandler(ch, frameWriter, connection, pingTimeoutMillis,
                                              !testInfo.getTags().contains(sendPingsOnNoActiveStreams));

        ch.pipeline().addLast(new TestIdleStateHandler(keepAlive));

        assertThat(ch.isOpen()).isTrue();
        assertThat(keepAlive.state()).isEqualTo(State.IDLE);
    }

    @AfterEach
    public void after() {
        assertThat(ch.finish()).isFalse();
    }

    @Test
    void testOnChannelIdle_WhenPingTimesOut_ShouldCloseConnection() throws Exception {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);
        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        assertThat(keepAlive.state()).isEqualTo(State.PING_SCHEDULED);

        promise.setSuccess();
        assertThat(keepAlive.state()).isEqualTo(State.PENDING_PING_ACK);
        waitUntilPingTimeout();
        assertThat(keepAlive.state()).isEqualTo(State.SHUTDOWN);

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void testOnChannelIdle_WhenWritePingFailsBecauseChannelIsClosed_ShouldSetStateToShutdown() throws
                                                                                               Exception {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        assertThat(keepAlive.state()).isEqualTo(State.PING_SCHEDULED);

        ch.close();
        assertThat(keepAlive.state()).isEqualTo(State.SHUTDOWN);

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void testOnChannelIdle_WhenWritePingFailsOfUnknownReason_ShouldSetStateToIdle() throws Exception {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        assertThat(keepAlive.state()).isEqualTo(State.PING_SCHEDULED);

        promise.setFailure(new Exception("Unknown reason"));
        assertThat(keepAlive.state()).isEqualTo(State.IDLE);

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void testOnChannelIdle_WhenPingAckIsReceivedBeforeTimeout_ShouldResetStateToIdle() throws Exception {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        assertThat(keepAlive.state()).isEqualTo(State.PING_SCHEDULED);

        promise.setSuccess();
        assertThat(keepAlive.state()).isEqualTo(State.PENDING_PING_ACK);

        keepAlive.onPingAck(keepAlive.lastPingPayload());
        assertThat(keepAlive.state()).isEqualTo(State.IDLE);

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void testOnChannelIdle_WhenAnyDataReadBeforeTimeout_ShouldResetStateToIdle() throws Exception {
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        assertThat(keepAlive.state()).isEqualTo(State.PING_SCHEDULED);

        promise.setSuccess();
        assertThat(keepAlive.state()).isEqualTo(State.PENDING_PING_ACK);

        keepAlive.onChannelRead();
        assertThat(keepAlive.state()).isEqualTo(State.IDLE);

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    @Tag("sendPingsOnNoActiveStreams")
    void testOnChannelIdle_WhenShouldNotSendPingsOnIdleAndActiveStreamsAreZero_ShouldCloseConnection()
            throws Exception {
        when(connection.numActiveStreams()).thenReturn(0);
        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);

        assertThat(keepAlive.state()).isEqualTo(State.SHUTDOWN);

        verify(frameWriter, never()).writePing(any(), anyBoolean(), anyLong(), any());
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    @Tag("sendPingsOnNoActiveStreams")
    void testOnChannelIdle_WhenShouldNotSendPingsOnIdleWithActiveStreams_ShouldCloseConnection()
            throws Exception {
        when(connection.numActiveStreams()).thenReturn(1);
        when(frameWriter.writePing(any(), eq(false), anyLong(), any())).thenReturn(promise);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
        assertThat(keepAlive.state()).isEqualTo(State.PING_SCHEDULED);

        promise.setSuccess();
        assertThat(keepAlive.state()).isEqualTo(State.PENDING_PING_ACK);

        keepAlive.onPingAck(keepAlive.lastPingPayload());
        assertThat(keepAlive.state()).isEqualTo(State.IDLE);

        verify(frameWriter).writePing(any(), eq(false), anyLong(), any());
        assertThat(ch.isOpen()).isTrue();
    }

    private void waitUntilPingTimeout() throws InterruptedException {
        Thread.sleep(pingTimeoutMillis * 3 / 2);
        ch.runPendingTasks();
    }

    private static final class TestIdleStateHandler extends ChannelInboundHandlerAdapter {
        private final Http2KeepAliveHandler keepAlive;

        TestIdleStateHandler(Http2KeepAliveHandler keepAlive) {
            this.keepAlive = keepAlive;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            keepAlive.onChannelIdle(ctx, (IdleStateEvent) evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            keepAlive.onChannelInactive();
        }
    }
}
