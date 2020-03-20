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
/*
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for {@link ExecutorServiceMetrics}.
 *
 * @author Clint Checketts
 * @author Jon Schneider
 * @author Johnny Lim
 * @author Sebastian Lövdahl
 */
class ExecutorServiceMetricsTest {

    // Forked from Micrometer 1.3.6
    // https://github.com/micrometer-metrics/micrometer/blob/e6ff3c2fe9542608a33a62b10fdf1222cd60feae/micrometer-core/src/test/java/io/micrometer/core/instrument/binder/jvm/ExecutorServiceMetricsTest.java

    private static final String METRIC_PREFIX = "armeria-executor";

    private final MeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, new MockClock());
    private final Iterable<Tag> userTags = Tags.of("userTagKey", "userTagValue");

    @DisplayName("Normal executor can be instrumented after being initialized")
    @Test
    void executor() throws InterruptedException {
        final CountDownLatch lock = new CountDownLatch(1);
        final Executor exec = r -> {
            r.run();
            lock.countDown();
        };
        final Executor executor = ExecutorServiceMetrics.monitor(registry, exec, "exec",
                                                                 METRIC_PREFIX, userTags);
        executor.execute(() -> { /* no-op */ });
        lock.await();

        assertThat(registry.get(METRIC_PREFIX + ".execution")
                           .tags(userTags).tag("name", "exec").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get(METRIC_PREFIX + ".idle").tags(userTags).tag("name", "exec").timer().count())
                .isEqualTo(1L);
    }

    @DisplayName("ExecutorService is casted from Executor when necessary")
    @Test
    void executorCasting() {
        final Executor exec = Executors.newFixedThreadPool(2);
        ExecutorServiceMetrics.monitor(registry, exec, "exec", METRIC_PREFIX, userTags);
        assertThreadPoolExecutorMetrics("exec");
    }

    @DisplayName("thread pool executor can be instrumented after being initialized")
    @Test
    void threadPoolExecutor() {
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        ExecutorServiceMetrics.monitor(registry, exec, "exec", METRIC_PREFIX, userTags);
        assertThreadPoolExecutorMetrics("exec");
    }

    @DisplayName("Scheduled thread pool executor can be instrumented after being initialized")
    @Test
    void scheduledThreadPoolExecutor() {
        final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
        ExecutorServiceMetrics.monitor(registry, exec, "exec", METRIC_PREFIX, userTags);
        assertThreadPoolExecutorMetrics("exec");
    }

    @DisplayName("ScheduledExecutorService is casted from Executor when necessary")
    @Test
    void scheduledThreadPoolExecutorAsExecutor() {
        final Executor exec = Executors.newScheduledThreadPool(2);
        ExecutorServiceMetrics.monitor(registry, exec, "exec", METRIC_PREFIX, userTags);
        assertThreadPoolExecutorMetrics("exec");
    }

    @DisplayName("ScheduledExecutorService is casted from ExecutorService when necessary")
    @Test
    void scheduledThreadPoolExecutorAsExecutorService() {
        final ExecutorService exec = Executors.newScheduledThreadPool(2);
        ExecutorServiceMetrics.monitor(registry, exec, "exec", METRIC_PREFIX, userTags);
        assertThreadPoolExecutorMetrics("exec");
    }

    @DisplayName("ExecutorService can be monitored with a default set of metrics")
    @Test
    void monitorExecutorService() throws InterruptedException {
        final ExecutorService pool =
                ExecutorServiceMetrics.monitor(registry, Executors.newSingleThreadExecutor(),
                                               "beep.pool", METRIC_PREFIX, userTags);
        final CountDownLatch taskStart = new CountDownLatch(1);
        final CountDownLatch taskComplete = new CountDownLatch(1);

        pool.submit(() -> {
            taskStart.countDown();
            assertThat(taskComplete.await(1, TimeUnit.SECONDS)).isTrue();
            return 0;
        });
        pool.submit(() -> { /* no-op */ });

        assertThat(taskStart.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.get(METRIC_PREFIX + ".queued").tags(userTags).tag("name", "beep.pool")
                           .gauge().value()).isEqualTo(1.0);

        taskComplete.countDown();

        pool.shutdown();
        assertThat(pool.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.get(METRIC_PREFIX).tags(userTags).timer().count()).isEqualTo(2L);
        assertThat(registry.get(METRIC_PREFIX + ".idle").tags(userTags).timer().count()).isEqualTo(2L);
        assertThat(registry.get(METRIC_PREFIX + ".queued").tags(userTags).gauge().value()).isEqualTo(0.0);
    }

    @DisplayName("ScheduledExecutorService can be monitored with a default set of metrics")
    @Test
    void monitorScheduledExecutorService() throws TimeoutException, ExecutionException, InterruptedException {
        final ScheduledExecutorService pool =
                ExecutorServiceMetrics.monitor(registry, Executors.newScheduledThreadPool(2),
                                               "scheduled.pool", METRIC_PREFIX, userTags);
        final CountDownLatch callableTaskStart = new CountDownLatch(1);
        final CountDownLatch runnableTaskStart = new CountDownLatch(1);
        final CountDownLatch callableTaskComplete = new CountDownLatch(1);
        final CountDownLatch runnableTaskComplete = new CountDownLatch(1);

        final Callable<Integer> scheduledBeepCallable = () -> {
            callableTaskStart.countDown();
            assertThat(callableTaskComplete.await(1, TimeUnit.SECONDS)).isTrue();
            return 1;
        };
        final ScheduledFuture<Integer> callableResult = pool.schedule(scheduledBeepCallable, 10,
                                                                      TimeUnit.MILLISECONDS);

        final Runnable scheduledBeepRunnable = () -> {
            runnableTaskStart.countDown();
            try {
                assertThat(runnableTaskComplete.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException("scheduled runnable interrupted before completion");
            }
        };
        final ScheduledFuture<?> runnableResult = pool.schedule(scheduledBeepRunnable, 15,
                                                                TimeUnit.MILLISECONDS);

        assertThat(registry.get(METRIC_PREFIX + ".scheduled.once").tags(userTags).tag("name", "scheduled.pool")
                           .counter().count()).isEqualTo(2);

        assertThat(callableTaskStart.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(runnableTaskStart.await(1, TimeUnit.SECONDS)).isTrue();

        callableTaskComplete.countDown();
        runnableTaskComplete.countDown();

        pool.shutdown();
        assertThat(pool.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(callableResult.get(1, TimeUnit.MINUTES)).isEqualTo(1);
        assertThat(runnableResult.get(1, TimeUnit.MINUTES)).isNull();

        assertThat(registry.get(METRIC_PREFIX).tags(userTags).timer().count()).isEqualTo(2L);
        assertThat(registry.get(METRIC_PREFIX + ".idle").tags(userTags).timer().count()).isEqualTo(0L);
    }

    @DisplayName("ScheduledExecutorService repetitive tasks can be monitored with a default set of metrics")
    @Test
    void monitorScheduledExecutorServiceWithRepetitiveTasks() throws InterruptedException {
        final ScheduledExecutorService pool =
                ExecutorServiceMetrics.monitor(registry, Executors.newScheduledThreadPool(1),
                                               "scheduled.pool", METRIC_PREFIX, userTags);
        final CountDownLatch fixedRateInvocations = new CountDownLatch(3);
        final CountDownLatch fixedDelayInvocations = new CountDownLatch(3);

        assertThat(registry.get(METRIC_PREFIX + ".scheduled.repetitively").tags(userTags).counter().count())
                .isEqualTo(0);
        assertThat(registry.get(METRIC_PREFIX).tags(userTags).timer().count()).isEqualTo(0L);

        final Runnable repeatedAtFixedRate = () -> {
            fixedRateInvocations.countDown();
            if (fixedRateInvocations.getCount() == 0) {
                throw new RuntimeException("finished execution");
            }
        };
        pool.scheduleAtFixedRate(repeatedAtFixedRate, 10, 10, TimeUnit.MILLISECONDS);

        final Runnable repeatedWithFixedDelay = () -> {
            fixedDelayInvocations.countDown();
            if (fixedDelayInvocations.getCount() == 0) {
                throw new RuntimeException("finished execution");
            }
        };
        pool.scheduleWithFixedDelay(repeatedWithFixedDelay, 5, 15, TimeUnit.MILLISECONDS);

        assertThat(registry.get(METRIC_PREFIX + ".scheduled.repetitively").tags(userTags).counter().count())
                .isEqualTo(2);

        assertThat(fixedRateInvocations.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fixedDelayInvocations.await(5, TimeUnit.SECONDS)).isTrue();

        pool.shutdown();
        assertThat(pool.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.get(METRIC_PREFIX).tags(userTags).timer().count()).isEqualTo(6L);
        assertThat(registry.get(METRIC_PREFIX + ".idle").tags(userTags).timer().count()).isEqualTo(0L);
    }

    private void assertThreadPoolExecutorMetrics(String executorName) {
        registry.get(METRIC_PREFIX + ".completed").tags(userTags).tag("name", executorName).meter();
        registry.get(METRIC_PREFIX + ".queued").tags(userTags).tag("name", executorName).gauge();
        registry.get(METRIC_PREFIX + ".queue.remaining").tags(userTags).tag("name", executorName).gauge();
        registry.get(METRIC_PREFIX + ".active").tags(userTags).tag("name", executorName).gauge();
        registry.get(METRIC_PREFIX + ".pool.size").tags(userTags).tag("name", executorName).gauge();
        registry.get(METRIC_PREFIX + ".idle").tags(userTags).tag("name", executorName).timer();
        registry.get(METRIC_PREFIX).tags(userTags).tag("name", executorName).timer();
    }
}
