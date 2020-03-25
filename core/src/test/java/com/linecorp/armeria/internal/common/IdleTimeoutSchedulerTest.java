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

import com.linecorp.armeria.testing.junit.common.EventLoopExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

class IdleTimeoutSchedulerTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
        ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
    }

    @AfterEach
    void tearDown() {
        channel.finish();
    }

    @CsvSource({
            "1000, 0, ALL_IDLE",
            "0, 1000, PING_IDLE",
    })
    @ParameterizedTest
    void testIdle(long allIdleTime, long pingIdleTime, IdleState state) {
        final long tolerance = 50;
        final AtomicLong lastIdleEventTime = new AtomicLong();
        final AtomicInteger counter = new AtomicInteger();
        final long idleTime = state == IdleState.ALL_IDLE ? allIdleTime : pingIdleTime;

        final IdleTimeoutScheduler idleTimeoutScheduler =
                new IdleTimeoutScheduler(allIdleTime, pingIdleTime, TimeUnit.MILLISECONDS, eventLoop.get()) {
                    @Override
                    protected void onIdleEvent(ChannelHandlerContext ctx, IdleStateEvent evt) {
                        final long oldIdleEventTime = lastIdleEventTime.getAndSet(System.nanoTime());
                        assertThat(evt.state()).isEqualTo(state);
                        assertThat(TimeUnit.NANOSECONDS.toMillis(lastIdleEventTime.get() - oldIdleEventTime))
                                .isBetween(idleTime - tolerance, idleTime + tolerance);
                        counter.incrementAndGet();
                    }
                };

        lastIdleEventTime.set(System.nanoTime());
        idleTimeoutScheduler.initialize(ctx);
        await().timeout(20, TimeUnit.SECONDS).untilAtomic(counter, Matchers.is(10));

        idleTimeoutScheduler.destroy();
    }

    @CsvSource({
            "1000, 0, ALL_IDLE",
            "0, 1000, PING_IDLE",
    })
    @ParameterizedTest
    void testKeepAlive(long allIdleTime, long pingIdleTime, IdleState state) throws InterruptedException {
        final long tolerance = 200;
        final AtomicLong lastIdleEventTime = new AtomicLong();
        final AtomicInteger counter = new AtomicInteger();
        final long idleTime = state == IdleState.ALL_IDLE ? allIdleTime : pingIdleTime;
        final Consumer<IdleTimeoutScheduler> activator =
                state == IdleState.ALL_IDLE ?
                IdleTimeoutScheduler::onReadOrWrite : IdleTimeoutScheduler::onPing;

        final IdleTimeoutScheduler idleTimeoutScheduler =
                new IdleTimeoutScheduler(allIdleTime, pingIdleTime, TimeUnit.MILLISECONDS, eventLoop.get()) {
                    @Override
                    protected void onIdleEvent(ChannelHandlerContext ctx, IdleStateEvent evt) {
                        final long oldIdleEventTime = lastIdleEventTime.getAndSet(System.nanoTime());
                        assertThat(evt.state()).isEqualTo(state);
                        assertThat(TimeUnit.NANOSECONDS.toMillis(lastIdleEventTime.get() - oldIdleEventTime))
                                .isBetween(idleTime - tolerance, idleTime + tolerance);
                        counter.incrementAndGet();
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
}
