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
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;

class ScheduledHealthCheckerTest {
    private static final Queue<Runnable> scheduledJobs = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        scheduledJobs.clear();
    }

    @Test
    void triggerAfterConstruct() {
        final CompletableFuture<HealthCheckStatus> future = new CompletableFuture<>();
        final ScheduledHealthChecker checker =
                new ScheduledHealthChecker(() -> future, Duration.ofSeconds(10), new MockExecutor());

        final Set<Future<?>> scheduledFuture = new HashSet<>();
        final Boolean[] result = new Boolean[1];
        checker.addListener(healthChecker -> result[0] = healthChecker.isHealthy());
        checker.startHealthChecker(scheduledFuture::add);
        assertThat(checker.isHealthy()).isFalse();
        assertThat(result[0]).isNull();
        assertThat(scheduledFuture).isEmpty();

        future.complete(new HealthCheckStatus(true, 5000));
        assertThat(checker.isHealthy()).isTrue();
        assertThat(result[0]).isTrue();
        assertThat(scheduledFuture).hasSize(1);
    }

    @Test
    void unhealthy() {
        final Queue<CompletableFuture<HealthCheckStatus>> queue = new ArrayDeque<>();
        final ScheduledHealthChecker checker =
                new ScheduledHealthChecker(() -> {
                    final CompletableFuture<HealthCheckStatus> future = new CompletableFuture<>();
                    queue.add(future);
                    return future;
                }, Duration.ofSeconds(10), new MockExecutor());
        final Set<Future<?>> scheduledFuture = new HashSet<>();
        final Boolean[] result = new Boolean[1];
        checker.addListener(healthChecker -> result[0] = healthChecker.isHealthy());
        checker.startHealthChecker(scheduledFuture::add);

        queue.poll().complete(new HealthCheckStatus(true, 5000));

        scheduledJobs.poll().run();
        queue.poll().complete(new HealthCheckStatus(false, 5000));
        assertThat(checker.isHealthy()).isFalse();
        assertThat(result[0]).isFalse();
        assertThat(scheduledFuture).hasSize(2);
    }

    private static class MockExecutor extends DefaultEventLoop {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduledJobs.add(command);
            return mock(ScheduledFuture.class);
        }
    }
}
