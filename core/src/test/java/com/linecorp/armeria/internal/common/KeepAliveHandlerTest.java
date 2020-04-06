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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.internal.common.KeepAliveHandler.PingState;
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

    @CsvSource({
            "1000, 0, CONNECTION_IDLE",
            "0, 1000, PING_IDLE",
    })
    @ParameterizedTest
    void testIdle(long connectionIdleTimeout, long pingInterval, IdleState state) {
        final long tolerance = 50;
        final AtomicLong lastIdleEventTime = new AtomicLong();
        final AtomicInteger counter = new AtomicInteger();
        final long idleTime = state == IdleState.CONNECTION_IDLE ? connectionIdleTimeout : pingInterval;

        final KeepAliveHandler idleTimeoutScheduler =
                new KeepAliveHandler(channel, "test", connectionIdleTimeout, pingInterval) {
                    @Override
                    void onIdleEvent(ChannelHandlerContext ctx, IdleStateEvent evt) {
                        final long oldIdleEventTime = lastIdleEventTime.getAndSet(System.nanoTime());
                        assertThat(evt.state()).isEqualTo(state);
                        assertThat(TimeUnit.NANOSECONDS.toMillis(lastIdleEventTime.get() - oldIdleEventTime))
                                .isBetween(idleTime - tolerance, idleTime + tolerance);
                        counter.incrementAndGet();
                    }

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        return null;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return false;
                    }
                };

        lastIdleEventTime.set(System.nanoTime());
        idleTimeoutScheduler.initialize(ctx);
        await().timeout(20, TimeUnit.SECONDS).untilAtomic(counter, Matchers.is(10));

        idleTimeoutScheduler.destroy();
    }

    @CsvSource({
            "1000, 0, CONNECTION_IDLE",
            "0, 1000, PING_IDLE",
    })
    @ParameterizedTest
    void testKeepAlive(long connectionIdleTimeout, long pingInterval, IdleState state)
            throws InterruptedException {
        final long tolerance = 200;
        final AtomicLong lastIdleEventTime = new AtomicLong();
        final AtomicInteger counter = new AtomicInteger();
        final long idleTime = state == IdleState.CONNECTION_IDLE ? connectionIdleTimeout : pingInterval;
        final Consumer<KeepAliveHandler> activator =
                state == IdleState.CONNECTION_IDLE ?
                KeepAliveHandler::onReadOrWrite : KeepAliveHandler::onPing;

        final KeepAliveHandler idleTimeoutScheduler =
                new KeepAliveHandler(channel, "test", connectionIdleTimeout, pingInterval) {
                    @Override
                    void onIdleEvent(ChannelHandlerContext ctx, IdleStateEvent evt) {
                        final long oldIdleEventTime = lastIdleEventTime.getAndSet(System.nanoTime());
                        assertThat(evt.state()).isEqualTo(state);
                        assertThat(TimeUnit.NANOSECONDS.toMillis(lastIdleEventTime.get() - oldIdleEventTime))
                                .isBetween(idleTime - tolerance, idleTime + tolerance);
                        counter.incrementAndGet();
                    }

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        return null;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return false;
                    }
                };

        lastIdleEventTime.set(System.nanoTime());
        idleTimeoutScheduler.initialize(ctx);

        await().timeout(10, TimeUnit.SECONDS).untilAtomic(counter, Matchers.is(5));
        for (int i = 0; i < 5; i++) {
            activator.accept(idleTimeoutScheduler);
            Thread.sleep(idleTime - 100);
        }
        await().timeout(10, TimeUnit.SECONDS).untilAtomic(counter, Matchers.is(5));

        idleTimeoutScheduler.destroy();
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
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return hasRequests;
                    }
                };

        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        keepAliveHandler.initialize(ctx);
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        keepAliveHandler.onReadOrWrite();
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        Thread.sleep(idleTimeout / 2);
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        Thread.sleep(idleTimeout);
        if (hasRequests) {
            assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);
        } else {
            assertThat(keepAliveHandler.state()).isEqualTo(PingState.SHUTDOWN);
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
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return hasRequests;
                    }
                };

        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        keepAliveHandler.initialize(ctx);
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        keepAliveHandler.onReadOrWrite();
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        keepAliveHandler.writePing(ctx);
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(PingState.PING_SCHEDULED));

        promise.setSuccess();
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(PingState.PENDING_PING_ACK));

        keepAliveHandler.onPing();
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        Thread.sleep(pingInterval * 2);
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.PENDING_PING_ACK);
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void resetPing(boolean resetPing) throws InterruptedException {
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
                    protected boolean pingResetsPreviousPing() {
                        return resetPing;
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return true;
                    }
                };

        keepAliveHandler.initialize(ctx);
        assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);

        keepAliveHandler.writePing(ctx);
        await().untilAsserted(() -> assertThat(keepAliveHandler.state()).isEqualTo(PingState.PING_SCHEDULED));

        if (resetPing) {
            keepAliveHandler.onReadOrWrite();
            assertThat(keepAliveHandler.state()).isEqualTo(PingState.IDLE);
        } else {
            keepAliveHandler.onReadOrWrite();
            assertThat(keepAliveHandler.state()).isEqualTo(PingState.PING_SCHEDULED);
        }
    }
}
