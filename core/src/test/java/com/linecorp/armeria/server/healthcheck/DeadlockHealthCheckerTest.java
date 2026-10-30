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
package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

class DeadlockHealthCheckerTest {

    @Test
    void healthyWhenNoDeadlockedThreads() {
        final ThreadMXBean threadMXBean = mock(ThreadMXBean.class);
        when(threadMXBean.isSynchronizerUsageSupported()).thenReturn(true);
        when(threadMXBean.findDeadlockedThreads()).thenReturn(null);

        final DeadlockHealthChecker checker = new DeadlockHealthChecker(threadMXBean);
        assertThat(checker.isHealthy()).isTrue();
        verify(threadMXBean, never()).findMonitorDeadlockedThreads();
    }

    @Test
    void unhealthyWhenDeadlockedThreadsExist() {
        final ThreadMXBean threadMXBean = mock(ThreadMXBean.class);
        when(threadMXBean.isSynchronizerUsageSupported()).thenReturn(true);
        when(threadMXBean.findDeadlockedThreads()).thenReturn(new long[] { 1, 2 });
        // A thread that is not alive anymore has no ThreadInfo.
        when(threadMXBean.getThreadInfo(any(long[].class))).thenReturn(new ThreadInfo[] { null, null });

        final DeadlockHealthChecker checker = new DeadlockHealthChecker(threadMXBean);
        assertThat(checker.isHealthy()).isFalse();
        // The result must not be cached by the checker itself; scheduling is the caller's responsibility.
        when(threadMXBean.findDeadlockedThreads()).thenReturn(null);
        assertThat(checker.isHealthy()).isTrue();
    }

    @Test
    void fallsBackToMonitorDeadlockDetection() {
        final ThreadMXBean threadMXBean = mock(ThreadMXBean.class);
        when(threadMXBean.isSynchronizerUsageSupported()).thenReturn(false);
        when(threadMXBean.findMonitorDeadlockedThreads()).thenReturn(new long[] { 1 });
        when(threadMXBean.getThreadInfo(any(long[].class))).thenReturn(new ThreadInfo[] { null });

        final DeadlockHealthChecker checker = new DeadlockHealthChecker(threadMXBean);
        assertThat(checker.isHealthy()).isFalse();
        verify(threadMXBean, never()).findDeadlockedThreads();
    }

    @Test
    void healthyWhenDetectionIsNotPermitted() {
        final ThreadMXBean threadMXBean = mock(ThreadMXBean.class);
        when(threadMXBean.isSynchronizerUsageSupported()).thenReturn(true);
        when(threadMXBean.findDeadlockedThreads()).thenThrow(new SecurityException("test"));

        final DeadlockHealthChecker checker = new DeadlockHealthChecker(threadMXBean);
        // It is impossible to tell whether a deadlock exists, so the checker must not mark the server down.
        assertThat(checker.isHealthy()).isTrue();
        assertThat(checker.isHealthy()).isTrue();
    }

    @Test
    void detectsRealDeadlock() throws InterruptedException {
        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        // ReentrantLock deadlocks are reported only when the JVM supports synchronizer usage monitoring.
        assumeThat(threadMXBean.isSynchronizerUsageSupported()).isTrue();
        assumeThat(threadMXBean.findDeadlockedThreads()).isNull();

        final ReentrantLock lock1 = new ReentrantLock();
        final ReentrantLock lock2 = new ReentrantLock();
        final CountDownLatch bothLocked = new CountDownLatch(2);
        // lockInterruptibly() is used instead of synchronized so that the deadlock can be resolved
        // by interrupting the threads once the test finishes.
        final Thread thread1 = new Thread(() -> lockInOrder(lock1, lock2, bothLocked), "deadlock-test-1");
        final Thread thread2 = new Thread(() -> lockInOrder(lock2, lock1, bothLocked), "deadlock-test-2");
        thread1.setDaemon(true);
        thread2.setDaemon(true);
        thread1.start();
        thread2.start();
        try {
            final DeadlockHealthChecker checker = new DeadlockHealthChecker(threadMXBean);
            await().untilAsserted(() -> assertThat(checker.isHealthy()).isFalse());

            // The information used for logging must point at each other.
            final long[] deadlockedThreadIds = threadMXBean.findDeadlockedThreads();
            assertThat(deadlockedThreadIds).containsExactlyInAnyOrder(thread1.getId(), thread2.getId());
            for (ThreadInfo threadInfo : threadMXBean.getThreadInfo(deadlockedThreadIds)) {
                assertThat(threadInfo.getLockName()).isNotNull();
                assertThat(threadInfo.getLockOwnerName()).isIn(thread1.getName(), thread2.getName());
                assertThat(threadInfo.getLockOwnerName()).isNotEqualTo(threadInfo.getThreadName());
            }
        } finally {
            thread1.interrupt();
            thread2.interrupt();
            thread1.join(5000);
            thread2.join(5000);
        }
        assertThat(thread1.isAlive()).isFalse();
        assertThat(thread2.isAlive()).isFalse();
        assertThat(threadMXBean.findDeadlockedThreads()).isNull();
    }

    @Test
    void ofDeadlockRejectsInvalidInterval() {
        assertThatThrownBy(() -> HealthChecker.ofDeadlock(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("checkInterval");
        assertThatThrownBy(() -> HealthChecker.ofDeadlock(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkInterval");
        assertThatThrownBy(() -> HealthChecker.ofDeadlock(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkInterval");
        // Sub-millisecond intervals are rejected because the scheduling resolution is a millisecond.
        assertThatThrownBy(() -> HealthChecker.ofDeadlock(Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkInterval");
    }

    @Test
    void ofDeadlockBecomesHealthyAfterFirstCheck() {
        assumeThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).isNull();

        final ListenableHealthChecker healthChecker = HealthChecker.ofDeadlock(Duration.ofMillis(100));
        // Unhealthy until the first check completes, which happens only after a Server starts.
        assertThat(healthChecker.isHealthy()).isFalse();

        final Server server = Server.builder()
                                    .service("/hc", HealthCheckService.of(healthChecker))
                                    .build();
        server.start().join();
        try {
            await().untilAsserted(() -> assertThat(healthChecker.isHealthy()).isTrue());
            final String uri = "http://localhost:" + server.activeLocalPort();
            assertThat(WebClient.of(uri).get("/hc").aggregate().join().status()).isSameAs(HttpStatus.OK);
        } finally {
            server.stop().join();
        }
    }

    private static void lockInOrder(ReentrantLock first, ReentrantLock second, CountDownLatch bothLocked) {
        try {
            first.lockInterruptibly();
            try {
                bothLocked.countDown();
                bothLocked.await();
                second.lockInterruptibly();
                second.unlock();
            } finally {
                first.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
