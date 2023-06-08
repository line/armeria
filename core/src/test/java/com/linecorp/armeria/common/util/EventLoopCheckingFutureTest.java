/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.testing.BlockingUtils;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

class EventLoopCheckingFutureTest {

    @RegisterExtension
    public static final EventLoopExtension eventLoop = new EventLoopExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static final ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @Mock private Appender<ILoggingEvent> appender;
    @Captor private ArgumentCaptor<ILoggingEvent> eventCaptor;

    @BeforeEach
    void setupLogger() {
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void joinOnEventLoop() {
        testBlockingOperationOnEventLoop(CompletableFuture::join);
    }

    @Test
    void joinOnEventLoopAfterCompletion() {
        testBlockingOperationOnEventLoopAfterCompletion(CompletableFuture::join);
    }

    @Test
    void joinOffEventLoop() throws Exception {
        testBlockingOperationOffEventLoop(CompletableFuture::join);
    }

    @Test
    void getOnEventLoop() {
        testBlockingOperationOnEventLoop(CompletableFuture::get);
    }

    @Test
    void getOnEventLoopAfterCompletion() {
        testBlockingOperationOnEventLoopAfterCompletion(CompletableFuture::get);
    }

    @Test
    void getOffEventLoop() throws Exception {
        testBlockingOperationOffEventLoop(CompletableFuture::get);
    }

    @Test
    void getTimeoutOnEventLoop() {
        testBlockingOperationOnEventLoop(future -> future.get(10, TimeUnit.SECONDS));
    }

    @Test
    void getTimeoutOnEventLoopAfterCompletion() {
        testBlockingOperationOnEventLoopAfterCompletion(future -> future.get(10, TimeUnit.SECONDS));
    }

    @Test
    void getTimeoutOffEventLoop() throws Exception {
        testBlockingOperationOffEventLoop(future -> future.get(10, TimeUnit.SECONDS));
    }

    private void testBlockingOperationOnEventLoop(EventLoopCheckingFutureTask task) {
        final EventLoopCheckingFuture<String> future = new EventLoopCheckingFuture<>();
        eventLoop.get().submit(() -> BlockingUtils.blockingRun(() -> task.run(future)));
        try {
            await().untilAsserted(() -> {
                verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
                assertThat(eventCaptor.getAllValues()).hasSizeGreaterThan(1)
                                                      .anySatisfy(this::assertWarned);
            });
        } finally {
            future.complete("complete");
        }
    }

    private void testBlockingOperationOnEventLoopAfterCompletion(EventLoopCheckingFutureTask task) {
        final EventLoopCheckingFuture<String> future = new EventLoopCheckingFuture<>();
        future.complete("complete");
        eventLoop.get().submit(() -> task.run(future)).syncUninterruptibly();
        verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).noneSatisfy(this::assertWarned);
    }

    private void testBlockingOperationOffEventLoop(EventLoopCheckingFutureTask task) throws Exception {
        final EventLoopCheckingFuture<String> future = new EventLoopCheckingFuture<>();
        final Future<?> submitFuture = CommonPools.blockingTaskExecutor()
                                                  .submit(() -> task.run(future));

        // Give time to make sure the task is invoked before future.complete() below.
        Thread.sleep(500);

        // Complete the future and ensure the logger was invoked.
        future.complete("complete");
        submitFuture.get();

        verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).noneSatisfy(this::assertWarned);
    }

    @SuppressWarnings("MethodMayBeStatic") // Method reference becomes too verbose if static.
    private void assertWarned(ILoggingEvent event) {
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getMessage()).startsWith("Calling a blocking method");
    }

    @Test
    void completedFuture() {
        final EventLoopCheckingFuture<String> future =
                EventLoopCheckingFuture.completedFuture("foo");
        assertThat(future).isCompletedWithValue("foo");
    }

    @Test
    void completedFutureWithNull() {
        final EventLoopCheckingFuture<?> future =
                EventLoopCheckingFuture.completedFuture(null);
        assertThat(future).isCompletedWithValue(null);
    }

    @Test
    void exceptionallyCompletedFuture() {
        final Throwable cause = new Throwable();
        final EventLoopCheckingFuture<?> future =
                EventLoopCheckingFuture.exceptionallyCompletedFuture(cause);
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                                        .hasCauseReference(cause);
    }

    @FunctionalInterface
    private interface EventLoopCheckingFutureTask {
        String run(CompletableFuture<String> future) throws Exception;
    }
}
