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

package com.linecorp.armeria.internal.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.testing.junit.common.EventLoopExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty.util.concurrent.Future;

class EventLoopCheckingCompletableFutureTest {

    @RegisterExtension
    public static final EventLoopExtension eventLoop = new EventLoopExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Mock private Appender<ILoggingEvent> appender;
    @Captor private ArgumentCaptor<ILoggingEvent> eventCaptor;

    @BeforeEach
    void setupLogger() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .detachAppender(appender);
    }

    @Test
    void joinOnEventLoop() {
        final EventLoopCheckingCompletableFuture<String> future = new EventLoopCheckingCompletableFuture<>();
        final Future<?> eventLoopFuture = eventLoop.get().submit((Runnable) future::join);
        future.complete("complete");
        eventLoopFuture.syncUninterruptibly();
        verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage()).startsWith("Calling a blocking method");
        });
    }

    @Test
    void joinOffEventLoop() {
        final EventLoopCheckingCompletableFuture<String> future = new EventLoopCheckingCompletableFuture<>();
        final AtomicBoolean joined = new AtomicBoolean();
        CommonPools.blockingTaskExecutor().execute(() -> {
            future.join();
            joined.set(true);
        });
        future.complete("complete");
        await().untilTrue(joined);
        verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).noneSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage()).startsWith("Calling a blocking method");
        });
    }

    @Test
    void getOnEventLoop() {
        final EventLoopCheckingCompletableFuture<String> future = new EventLoopCheckingCompletableFuture<>();
        final Future<?> eventLoopFuture = eventLoop.get().submit((Callable<String>) future::get);
        future.complete("complete");
        eventLoopFuture.syncUninterruptibly();
        verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage()).startsWith("Calling a blocking method");
        });
    }

    @Test
    void getTimeoutOnEventLoop() {
        final EventLoopCheckingCompletableFuture<String> future = new EventLoopCheckingCompletableFuture<>();
        final Future<?> eventLoopFuture = eventLoop.get().submit(() -> future.get(10, TimeUnit.SECONDS));
        future.complete("complete");
        eventLoopFuture.syncUninterruptibly();
        verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage()).startsWith("Calling a blocking method");
        });
    }
}
