/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultBlockingTaskExecutorTest {

    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;
    private BlockingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(2);
        executor = new DefaultBlockingTaskExecutor(scheduledThreadPoolExecutor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void executeTracksNumPendingTasks() throws Exception {
        assertThat(executor.numPendingTasks()).isZero();

        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(1);

        executor.execute(() -> {
            startLatch.countDown();
            try {
                finishLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        startLatch.await();
        assertThat(executor.numPendingTasks()).isOne();

        finishLatch.countDown();
        await().untilAsserted(() -> assertThat(executor.numPendingTasks()).isZero());
    }

    @Test
    void submitTracksNumPendingTasksAndCancellation() throws Exception {
        final CountDownLatch blockLatch = new CountDownLatch(1);
        final CountDownLatch startLatch = new CountDownLatch(2);

        // Fill both threads
        executor.submit(() -> {
            startLatch.countDown();
            blockLatch.await();
            return null;
        });
        executor.submit(() -> {
            startLatch.countDown();
            blockLatch.await();
            return null;
        });

        startLatch.await();
        assertThat(executor.numPendingTasks()).isEqualTo(2);

        // Submit a third task that is queued
        final Future<String> queuedFuture = executor.submit(() -> "done");
        assertThat(executor.numPendingTasks()).isEqualTo(3);

        // Cancel the queued task before it starts running
        assertThat(queuedFuture.cancel(false)).isTrue();
        assertThat(executor.numPendingTasks()).isEqualTo(2);

        blockLatch.countDown();
        await().untilAsserted(() -> assertThat(executor.numPendingTasks()).isZero());
    }

    @Test
    void scheduleTracksNumPendingTasksAndCancellation() {
        assertThat(executor.numPendingTasks()).isZero();

        final ScheduledFuture<?> future = executor.schedule(() -> {}, 1, TimeUnit.HOURS);
        assertThat(executor.numPendingTasks()).isOne();

        assertThat(future.cancel(false)).isTrue();
        assertThat(executor.numPendingTasks()).isZero();
    }

    @Test
    void scheduleAtFixedRateTracksNumPendingTasks() throws Exception {
        assertThat(executor.numPendingTasks()).isZero();

        final CountDownLatch countLatch = new CountDownLatch(3);
        final ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                countLatch::countDown, 0, 10, TimeUnit.MILLISECONDS);

        // Stays 1 even after multiple runs
        countLatch.await();
        assertThat(executor.numPendingTasks()).isOne();

        future.cancel(true);
        assertThat(executor.numPendingTasks()).isZero();
    }

    @Test
    void scheduleAtFixedRateDecrementsOnUncaughtException() {
        assertThat(executor.numPendingTasks()).isZero();

        executor.scheduleAtFixedRate(() -> {
            throw new RuntimeException("boom");
        }, 0, 10, TimeUnit.MILLISECONDS);

        await().untilAsserted(() -> assertThat(executor.numPendingTasks()).isZero());
    }

    @Test
    void invokeAllTracksNumPendingTasks() throws Exception {
        assertThat(executor.numPendingTasks()).isZero();

        final List<Callable<String>> tasks = Arrays.asList(
                () -> "a",
                () -> "b",
                () -> "c"
        );

        final List<Future<String>> results = executor.invokeAll(tasks);
        assertThat(results).hasSize(3);
        assertThat(executor.numPendingTasks()).isZero();
    }

    @Test
    void invokeAnyTracksNumPendingTasks() throws Exception {
        assertThat(executor.numPendingTasks()).isZero();

        final List<Callable<String>> tasks = Arrays.asList(
                () -> "result",
                () -> "other"
        );

        final String result = executor.invokeAny(tasks);
        assertThat(result).isNotNull();
        await().untilAsserted(() -> assertThat(executor.numPendingTasks()).isZero());
    }
}
