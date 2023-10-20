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
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.assertj.core.data.Percentage;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.common.AbstractKeepAliveHandler.PingState;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;

@FlakyTest
@MockitoSettings(strictness = Strictness.LENIENT)
class KeepAliveHandlerTest {

    private static final String CONNECTION_LIFETIME = "armeria.connections.lifespan";

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;
    private MeterRegistry meterRegistry;
    private Timer keepAliveTimer;

    @BeforeEach
    void setUp() {
        final EventLoop eventLoop = KeepAliveHandlerTest.eventLoop.get();
        channel = spy(new EmbeddedChannel());
        when(channel.eventLoop()).thenReturn(eventLoop);
        ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        meterRegistry = new SimpleMeterRegistry();
        keepAliveTimer = MoreMeters.newTimer(meterRegistry, CONNECTION_LIFETIME, ImmutableList.of());

        // Warm up the event loop to reduce timing errors.
        eventLoop.submit(() -> {
        }).syncUninterruptibly();
    }

    @AfterEach
    void tearDown() {
        channel.finish();
    }

    @Test
    void testIdle() {
        final AtomicInteger counter = new AtomicInteger();

        final AbstractKeepAliveHandler idleTimeoutScheduler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, 1000, 0, 0, 0, false) {

                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        return channel.newSucceededFuture();
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        counter.incrementAndGet();
                        return false;
                    }
                };

        idleTimeoutScheduler.initialize(ctx);
        await().timeout(4, TimeUnit.SECONDS).untilAtomic(counter, Matchers.is(1));

        // add another listener to ensure all closeFuture listeners are invoked at this point
        ctx.channel().closeFuture().addListener(unused -> counter.incrementAndGet());
        await().timeout(4, TimeUnit.SECONDS).untilAtomic(counter, Matchers.is(2));

        await().untilAsserted(() -> assertMeter(CONNECTION_LIFETIME + "#total", 1, withinPercentage(25)));
        idleTimeoutScheduler.destroy();
    }

    @Test
    void testPing() {
        final Stopwatch stopwatch = Stopwatch.createStarted();

        final AbstractKeepAliveHandler idleTimeoutScheduler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, 0, 1000, 0, 0, false) {

                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        stopwatch.stop();
                        return channel.newSucceededFuture();
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return false;
                    }
                };

        idleTimeoutScheduler.initialize(ctx);
        await().until(stopwatch::isRunning, Matchers.is(false));
        final Duration elapsed = stopwatch.elapsed();
        assertThat(elapsed.toMillis()).isBetween(1000L, 5000L);
        await().untilAsserted(() -> assertMeter(CONNECTION_LIFETIME + "#count", 0));
        idleTimeoutScheduler.destroy();
    }

    @Test
    void disableMaxConnectionAge() {
        final long maxConnectionAgeMillis = 0;
        final AbstractKeepAliveHandler
                keepAliveHandler = new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, 0, 0,
                                                                maxConnectionAgeMillis, 0, false) {
            @Override
            public boolean isHttp2() {
                return false;
            }

            @Override
            public void onPingAck(long data) {}

            @Override
            protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                return null;
            }

            @Override
            protected boolean pingResetsPreviousPing() {
                return false;
            }

            @Override
            protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                return false;
            }
        };
        keepAliveHandler.initialize(ctx);

        await().untilAsserted(() -> assertMeter(CONNECTION_LIFETIME + "#count", 0));
        assertThat(keepAliveHandler.needsDisconnection()).isFalse();
    }

    @Test
    void testMaxConnectionAge() throws InterruptedException {
        final long maxConnectionAgeMillis = 500;
        final AbstractKeepAliveHandler
                keepAliveHandler = new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, 0, 0,
                                                                maxConnectionAgeMillis, 0, false) {
            @Override
            public boolean isHttp2() {
                return false;
            }

            @Override
            public void onPingAck(long data) {}

            @Override
            protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                return null;
            }

            @Override
            protected boolean pingResetsPreviousPing() {
                return false;
            }

            @Override
            protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                return false;
            }
        };
        keepAliveHandler.initialize(ctx);

        final Stopwatch stopwatch = Stopwatch.createStarted();
        await().untilAsserted(() -> assertThat(keepAliveHandler.needsDisconnection()).isTrue());
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isBetween(500L, 1000L);
    }

    @Test
    void shouldNotWritePingIfConnectionIsActive() throws InterruptedException {
        final AtomicLong lastIdleEventTime = new AtomicLong();
        final AtomicInteger pingCounter = new AtomicInteger();
        final long pingInterval = 1000;

        final AbstractKeepAliveHandler pingScheduler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, 0,
                                             pingInterval, 0, 0, false) {

                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        pingCounter.incrementAndGet();
                        return channel.newSucceededFuture();
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        return true;
                    }
                };

        lastIdleEventTime.set(System.nanoTime());
        pingScheduler.initialize(ctx);

        for (int i = 0; i < 10; i++) {
            pingScheduler.onPing();
            Thread.sleep(pingInterval - 500);
        }
        assertThat(pingScheduler.isClosing()).isFalse();
        assertThat(pingCounter).hasValue(0);

        await().timeout(pingInterval, TimeUnit.MILLISECONDS).untilAtomic(pingCounter, Matchers.is(1));
        pingScheduler.destroy();
    }

    @Test
    void testKeepAliveWithIdleTimeout() throws InterruptedException {
        final AtomicLong lastIdleEventTime = new AtomicLong();
        final AtomicInteger idleCounter = new AtomicInteger();
        final long idleTimeout = 2000;

        final AbstractKeepAliveHandler idleTimeoutScheduler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, idleTimeout,
                                             0, 0, 0, false) {

                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        // Should never reach here.
                        throw new Error();
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        idleCounter.incrementAndGet();
                        return false;
                    }
                };

        lastIdleEventTime.set(System.nanoTime());
        idleTimeoutScheduler.initialize(ctx);

        for (int i = 0; i < 10; i++) {
            idleTimeoutScheduler.onReadOrWrite();
            Thread.sleep(idleTimeout - 1000);
            // Make sure that a connection was not closed by an idle timeout handler.
            assertThat(idleTimeoutScheduler.isClosing()).isFalse();
        }
        assertThat(idleCounter).hasValue(0);

        await().timeout(idleTimeout * 2, TimeUnit.MILLISECONDS).untilAtomic(idleCounter, Matchers.is(1));
        await().untilAsserted(() -> assertMeter(CONNECTION_LIFETIME + "#count", 1));
        idleTimeoutScheduler.destroy();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void testKeepAliveOnPing(boolean keepAliveOnPing) throws InterruptedException {
        final AtomicInteger idleCounter = new AtomicInteger();
        final long idleTimeout = 2000;

        final AbstractKeepAliveHandler idleTimeoutScheduler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, idleTimeout,
                                             0, 0, 0, keepAliveOnPing) {

                    @Override
                    public boolean isHttp2() {
                        return true;
                    }

                    @Override
                    public void onPingAck(long data) {}

                    @Override
                    protected boolean pingResetsPreviousPing() {
                        return true;
                    }

                    @Override
                    protected ChannelFuture writePing(ChannelHandlerContext ctx) {
                        // Should never reach here.
                        throw new Error();
                    }

                    @Override
                    protected boolean hasRequestsInProgress(ChannelHandlerContext ctx) {
                        idleCounter.incrementAndGet();
                        return false;
                    }
                };

        idleTimeoutScheduler.initialize(ctx);

        for (int i = 0; i < 10; i++) {
            idleTimeoutScheduler.onPing();
            Thread.sleep(idleTimeout - 500);
            // Make sure that a connection was not closed by an idle timeout handler base on keepAliveOnPing.
            if (i != 0) {
                // When keepAliveOnPing is false, the connection will be closed after the first iteration.
                assertThat(idleTimeoutScheduler.isClosing()).isEqualTo(!keepAliveOnPing);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void checkReadOrWrite(boolean hasRequests) throws InterruptedException {
        final long idleTimeout = 10000;
        final long pingInterval = 0;
        final long maxConnectionAgeMillis = 0;
        final ChannelFuture channelFuture = channel.newPromise();

        final AbstractKeepAliveHandler keepAliveHandler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, idleTimeout, pingInterval,
                                             maxConnectionAgeMillis, 0, false) {
                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

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
        final long maxConnectionAgeMillis = 0;
        final ChannelPromise promise = channel.newPromise();
        final int maxNumRequestsPerConnection = 0;
        final AbstractKeepAliveHandler keepAliveHandler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, idleTimeout, pingInterval,
                                             maxConnectionAgeMillis, maxNumRequestsPerConnection, false) {
                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

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
        final Stopwatch stopwatch = Stopwatch.createStarted();
        await().until(keepAliveHandler::state, Matchers.is(PingState.SHUTDOWN));
        final Duration elapsed = stopwatch.elapsed();
        assertThat(elapsed.toMillis()).isBetween(pingInterval, idleTimeout - 1000);
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void resetPing(boolean resetPing) {
        final long idleTimeout = 10000;
        final long pingInterval = 1000;
        final long maxConnectionAgeMillis = 0;
        final int maxNumRequestsPerConnection = 0;
        final ChannelPromise promise = channel.newPromise();
        final AbstractKeepAliveHandler keepAliveHandler =
                new AbstractKeepAliveHandler(channel, "test", keepAliveTimer, idleTimeout, pingInterval,
                                             maxConnectionAgeMillis, maxNumRequestsPerConnection, false) {
                    @Override
                    public boolean isHttp2() {
                        return false;
                    }

                    @Override
                    public void onPingAck(long data) {}

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

    private void assertMeter(String name, double expected) {
        assertThat(MoreMeters.measureAll(meterRegistry)).anySatisfy((name0, value) -> {
            assertThat(name0).isEqualTo(name);
            assertThat(value).isEqualTo(expected);
        });
    }

    private void assertMeter(String name, double expected, Percentage percentage) {
        assertThat(MoreMeters.measureAll(meterRegistry)).anySatisfy((name0, value) -> {
            assertThat(name0).isEqualTo(name);
            assertThat(value).isCloseTo(expected, percentage);
        });
    }
}
