/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.google.common.base.Ticker;

import com.linecorp.armeria.common.util.ThreadFactories;

class GracefulShutdownSupportTest {

    private static final long QUIET_PERIOD_NANOS = 10000;

    @Mock
    private Ticker ticker;

    private GracefulShutdownSupport support;
    private ThreadPoolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                ThreadFactories.newThreadFactory("graceful-shutdown-test", true));

        support = GracefulShutdownSupport.create(Duration.ofNanos(QUIET_PERIOD_NANOS), executor, ticker);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void testDisabled() {
        final GracefulShutdownSupport support = GracefulShutdownSupport.createDisabled();
        assertThat(support.isShuttingDown()).isFalse();
        assertThat(support.completedQuietPeriod()).isTrue();
        assertThat(support.isShuttingDown()).isTrue();
        support.inc();
        assertThat(support.pendingResponses()).isOne();
        assertThat(support.completedQuietPeriod()).isTrue();

        // pendingResponses must be updated even if disabled, because it's part of metrics.
        support.dec();
        assertThat(support.pendingResponses()).isZero();
    }

    @Test
    void testIsShutdown() {
        // completedQuietPeriod() must make isShuttingDown() start to return true.
        assertThat(support.isShuttingDown()).isFalse();
        support.completedQuietPeriod();
        assertThat(support.isShuttingDown()).isTrue();
    }

    @Test
    void noRequestsNotPassedQuietPeriod() {
        assertWithoutPendingTasks(false, 0, 43, QUIET_PERIOD_NANOS - 1);
    }

    @Test
    void noRequestsPassedQuietPeriod() {
        assertWithoutPendingTasks(true, 0, -43, QUIET_PERIOD_NANOS);
    }

    @Test
    void activeRequestsNotPassedQuietPeriod() throws Exception {
        support.inc();
        support.inc();
        verify(ticker, never()).read();

        support.dec();
        verify(ticker, times(1)).read();

        assertWithPendingTasks(1, -QUIET_PERIOD_NANOS * 2);
    }

    @Test
    void activeRequestsPassedQuietPeriod() throws Exception {
        support.inc();
        support.inc();
        verify(ticker, never()).read();

        support.dec();
        verify(ticker, times(1)).read();

        assertWithPendingTasks(1, 3);
    }

    @Test
    void noActiveRequestsNotPassedQuietPeriod() throws Exception {
        when(ticker.read()).thenReturn(1L);
        support.inc();
        support.inc();
        verify(ticker, never()).read();

        support.dec();
        verify(ticker, times(1)).read();

        support.dec();
        verify(ticker, times(2)).read();

        assertWithoutPendingTasks(false, 2, 3, QUIET_PERIOD_NANOS - 1);
    }

    @Test
    void noActiveRequestsPassedQuietPeriod() throws Exception {
        when(ticker.read()).thenReturn(1L);
        support.inc();
        support.inc();
        verify(ticker, never()).read();

        support.dec();
        verify(ticker, times(1)).read();
        support.dec();
        verify(ticker, times(2)).read();

        assertWithoutPendingTasks(true, 2, 3, QUIET_PERIOD_NANOS);
    }

    @Test
    void activeBlockingTaskNotPassedQuietPeriod() throws Exception {
        submitLongTask();
        assertWithPendingTasks(0, -42);
    }

    @Test
    void activeBlockingTaskPassedQuietPeriod() throws Exception {
        submitLongTask();
        assertWithPendingTasks(0, 42);
    }

    @Test
    void testQuietPeriodExtensionOnRequest() throws Exception {
        // Shutdown starts at T+1ns.
        when(ticker.read()).thenReturn(1L);
        assertThat(support.completedQuietPeriod()).isFalse();
        verify(ticker, times(2)).read();

        // Handle a request during the quiet period.
        support.inc();
        verify(ticker, times(2)).read();

        // Even during the quiet period, pending request will fail the check.
        assertThat(support.completedQuietPeriod()).isFalse();
        verify(ticker, times(2)).read();

        // Handle a response so that there are no pending requests left.
        // The quiet period will be extended by 2ns because the response is handled at T+3ns.
        when(ticker.read()).thenReturn(3L);
        support.dec();
        verify(ticker, times(3)).read();

        // The quiet period should not end before 'T+(3ns+QUIET_PERIOD_NANOS)'.
        when(ticker.read()).thenReturn(QUIET_PERIOD_NANOS + 2);
        assertThat(support.completedQuietPeriod()).isFalse();
        verify(ticker, times(4)).read();

        // The quiet period should end at 'T+(3ns+QUIET_PERIOD_NANOS)'.
        when(ticker.read()).thenReturn(QUIET_PERIOD_NANOS + 3);
        assertThat(support.completedQuietPeriod()).isTrue();
        verify(ticker, times(5)).read();
    }

    private void assertWithoutPendingTasks(boolean expectedReturnValue, int numTickerReadsSoFar,
                                           long shutdownStartTimeNanos, long elapsedTimeNanos) {

        // First completedQuietPeriod() should always return false,
        // because it considered its first invocation as the beginning of the graceful shutdown.
        when(ticker.read()).thenReturn(shutdownStartTimeNanos);
        assertThat(support.completedQuietPeriod()).isFalse();
        verify(ticker, times(numTickerReadsSoFar += 2)).read();

        when(ticker.read()).thenReturn(shutdownStartTimeNanos + elapsedTimeNanos);
        assertThat(support.completedQuietPeriod()).isEqualTo(expectedReturnValue);
        verify(ticker, times(numTickerReadsSoFar + 1)).read();
    }

    private void assertWithPendingTasks(int numTickerReadsSoFar, long shutdownStartTimeNanos) {
        // First completedQuietPeriod() should always return false,
        // because it considered its first invocation as the beginning of the graceful shutdown.
        when(ticker.read()).thenReturn(shutdownStartTimeNanos);
        assertThat(support.completedQuietPeriod()).isFalse();
        verify(ticker, times(++numTickerReadsSoFar)).read();

        assertThat(support.completedQuietPeriod()).isFalse();
        verify(ticker, times(numTickerReadsSoFar)).read();
    }

    private void submitLongTask() {
        final AtomicBoolean running = new AtomicBoolean();
        executor.execute(() -> {
            running.set(true);
            try {
                Thread.sleep(100000);
            } catch (InterruptedException ignored) {
                // Ignored
            }
        });
        await().untilTrue(running);
    }
}
