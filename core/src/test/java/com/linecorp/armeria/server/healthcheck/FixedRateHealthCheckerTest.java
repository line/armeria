/*
 * Copyright 2021 LINE Corporation
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
 * under the Licenses
 */

package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class FixedRateHealthCheckerTest {

    private static final Queue<Runnable> scheduledJobs = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        scheduledJobs.clear();
    }

    @Test
    void triggerAfterConstruct() {
        final Queue<CompletableFuture<Boolean>> queue = new ArrayDeque<>();
        final FixedRateHealthChecker checker =
                new FixedRateHealthChecker(() -> {
                    final CompletableFuture<Boolean> future = new CompletableFuture<>();
                    queue.add(future);
                    return future;
                }, Duration.ofSeconds(10), 0.2, new MockExecutor());
        assertThat(checker.isHealthy()).isFalse();

        scheduledJobs.peek().run();
        queue.poll().complete(true);
        assertThat(checker.isHealthy()).isTrue();
    }

    @Test
    void unhealthy() {
        final Queue<CompletableFuture<Boolean>> queue = new ArrayDeque<>();
        final FixedRateHealthChecker checker =
                new FixedRateHealthChecker(() -> {
                    final CompletableFuture<Boolean> future = new CompletableFuture<>();
                    queue.add(future);
                    return future;
                }, Duration.ofSeconds(10), 0.2, new MockExecutor());
        assertThat(checker.isHealthy()).isFalse();

        scheduledJobs.peek().run();
        queue.poll().complete(true);
        assertThat(checker.isHealthy()).isTrue();

        scheduledJobs.peek().run();
        queue.poll().complete(false);
        assertThat(checker.isHealthy()).isFalse();
    }

    @Test
    void healthy() {
        final Queue<CompletableFuture<Boolean>> queue = new ArrayDeque<>();
        final FixedRateHealthChecker checker =
                new FixedRateHealthChecker(() -> {
                    final CompletableFuture<Boolean> future = new CompletableFuture<>();
                    queue.add(future);
                    return future;
                }, Duration.ofSeconds(10), 0.2, new MockExecutor());
        assertThat(checker.isHealthy()).isFalse();

        scheduledJobs.peek().run();
        queue.poll().complete(false);
        assertThat(checker.isHealthy()).isFalse();

        scheduledJobs.peek().run();
        queue.poll().complete(true);
        assertThat(checker.isHealthy()).isTrue();
    }

    private static class MockExecutor extends DefaultEventLoop {
        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            scheduledJobs.add(command);
            return mock(ScheduledFuture.class);
        }
    }
}
