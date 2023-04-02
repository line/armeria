/*
 *  Copyright 2023 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;

class EventLoopMetricsTest {

    private class BlockMe extends CountDownLatch implements Runnable {

        AtomicInteger run = new AtomicInteger();

        BlockMe() {
            super(1);
        }

        @Override
        public void run() {
            run.incrementAndGet();
            try {
                await();
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    void test() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final EventLoopMetrics.Self metrics =
                new EventLoopMetrics.Self(
                        registry,
                        new MeterIdPrefix("foo")
                );

        final BlockMe task = new BlockMe();

        final EventLoopGroup workers = new DefaultEventLoopGroup(2);
        // Block both executors
        workers.submit(task);
        workers.submit(task);

        await().untilAtomic(task.run, Matchers.equalTo(2));

        workers.submit(() -> {});

        metrics.add(workers);

        // Check that API works as expected
        assertThat(metrics.pendingTasks()).isEqualTo(1.0);
        assertThat(metrics.numWorkers()).isEqualTo(2.0);

        // Check that metrics are exported
        assertThat(MoreMeters.measureAll(registry))
            .containsEntry("foo.event.loop.workers#value", 2.0)
            .containsEntry("foo.event.loop.pending.tasks#value", 1.0);

        // Release & shutdown threads
        task.countDown();

        await().untilAsserted(() ->
            assertThat(
                    MoreMeters.measureAll(registry))
                    .containsEntry("foo.event.loop.workers#value", 2.0)
                    .containsEntry("foo.event.loop.pending.tasks#value", 0.0));

        workers.shutdownGracefully();
    }
}
