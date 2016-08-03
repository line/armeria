/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.base.Ticker;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.DefaultThreadFactory;

public class GracefulShutdownHandlerTest {

    private static final long QUIET_PERIOD_NANOS = 10000;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private Ticker ticker;

    @Mock
    private ChannelHandlerContext channel;

    @Mock
    private HttpRequest requestStart;

    @Mock
    private LastHttpContent responseEnd;

    @Mock
    private ChannelPromise promise;

    private GracefulShutdownHandler handler;
    private ThreadPoolExecutor executor;

    @Before
    public void setUp() {
        executor = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                new DefaultThreadFactory(GracefulShutdownHandlerTest.class, true));

        handler = new GracefulShutdownHandler(
                Duration.ofNanos(QUIET_PERIOD_NANOS),
                executor, ticker);
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void noRequestsNotPassedQuietPeriod() {
        assertWithoutPendingTasks(false, 0, 1, QUIET_PERIOD_NANOS - 1);
    }

    @Test
    public void noRequestsPassedQuietPeriod() {
        assertWithoutPendingTasks(true, 0, -QUIET_PERIOD_NANOS * 2, QUIET_PERIOD_NANOS);
    }

    @Test
    public void activeRequestsNotPassedQuietPeriod() throws Exception {
        handler.channelRead(channel, requestStart);
        handler.channelRead(channel, requestStart);
        verify(ticker, never()).read();

        handler.write(channel, responseEnd, promise);
        verify(ticker, times(1)).read();

        assertWithPendingTasks(1, -QUIET_PERIOD_NANOS * 2);
    }

    @Test
    public void activeRequestsPassedQuietPeriod() throws Exception {
        handler.channelRead(channel, requestStart);
        handler.channelRead(channel, requestStart);
        verify(ticker, never()).read();

        handler.write(channel, responseEnd, promise);
        verify(ticker, times(1)).read();

        assertWithPendingTasks(1, 1);
    }

    @Test
    public void noActiveRequestsNotPassedQuietPeriod() throws Exception {
        handler.channelRead(channel, requestStart);
        handler.channelRead(channel, requestStart);
        verify(ticker, never()).read();

        handler.write(channel, responseEnd, promise);
        verify(ticker, times(1)).read();

        handler.write(channel, responseEnd, promise);
        verify(ticker, times(2)).read();

        assertWithoutPendingTasks(false, 2, 1, QUIET_PERIOD_NANOS - 1);
    }

    @Test
    public void noActiveRequestsPassedQuietPeriod() throws Exception {
        handler.channelRead(channel, requestStart);
        handler.channelRead(channel, requestStart);
        verify(ticker, never()).read();

        handler.write(channel, responseEnd, promise);
        verify(ticker, times(1)).read();
        handler.write(channel, responseEnd, promise);
        verify(ticker, times(2)).read();

        assertWithoutPendingTasks(true, 2, QUIET_PERIOD_NANOS, QUIET_PERIOD_NANOS);
    }

    @Test
    public void activeBlockingTaskNotPassedQuietPeriod() throws Exception {
        submitLongTask();
        assertWithPendingTasks(0, -42);
    }

    @Test
    public void activeBlockingTaskPassedQuietPeriod() throws Exception {
        submitLongTask();
        assertWithPendingTasks(0, 42);
    }

    @Test
    public void testQuietPeriodExtensionOnRequest() throws Exception {
        final long deltaNanos = QUIET_PERIOD_NANOS / 2;
        long timeNanos = 0;

        when(ticker.read()).thenReturn(timeNanos);
        assertFalse(handler.completedQuietPeriod());
        verify(ticker, times(2)).read();

        // Handle a request during the quiet period.
        handler.channelRead(channel, requestStart);
        verify(ticker, times(2)).read();

        // Even during the quiet period, pending request will fail the check.
        assertFalse(handler.completedQuietPeriod());
        verify(ticker, times(2)).read();

        // Handle a response so that there are no pending requests left.
        timeNanos += deltaNanos;
        when(ticker.read()).thenReturn(timeNanos);
        handler.write(channel, responseEnd, promise);
        verify(ticker, times(3)).read();

        // The quiet period should be extended by 'deltaNanos' due to the response handled above.
        timeNanos += deltaNanos;
        when(ticker.read()).thenReturn(timeNanos);
        assertFalse(handler.completedQuietPeriod());
        verify(ticker, times(4)).read();

        // The quiet period should end after 'QUIET_PERIOD_NANOS + deltaNanos'.
        timeNanos += deltaNanos;
        when(ticker.read()).thenReturn(timeNanos);
        assertTrue(handler.completedQuietPeriod());
        verify(ticker, times(5)).read();
    }

    private void assertWithoutPendingTasks(boolean expectedReturnValue, int numTickerReadsSoFar,
                                           long shutdownStartTimeNanos, long elapsedTimeNanos) {

        // First completedQuietPeriod() should always return false,
        // because it considered its first invocation as the beginning of the graceful shutdown.
        when(ticker.read()).thenReturn(shutdownStartTimeNanos);
        assertFalse(handler.completedQuietPeriod());
        verify(ticker, times(numTickerReadsSoFar += 2)).read();

        when(ticker.read()).thenReturn(shutdownStartTimeNanos + elapsedTimeNanos);
        assertEquals(expectedReturnValue, handler.completedQuietPeriod());
        verify(ticker, times(numTickerReadsSoFar + 1)).read();
    }

    private void assertWithPendingTasks(int numTickerReadsSoFar, long shutdownStartTimeNanos) {
        // First completedQuietPeriod() should always return false,
        // because it considered its first invocation as the beginning of the graceful shutdown.
        when(ticker.read()).thenReturn(shutdownStartTimeNanos);
        assertFalse(handler.completedQuietPeriod());
        verify(ticker, times(++numTickerReadsSoFar)).read();

        assertFalse(handler.completedQuietPeriod());
        verify(ticker, times(numTickerReadsSoFar)).read();
    }

    private void submitLongTask() {
        executor.execute(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ignored) {}
        });
    }
}
