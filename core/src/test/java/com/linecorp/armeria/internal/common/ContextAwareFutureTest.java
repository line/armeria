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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

class ContextAwareFutureTest {

    // TODO(minwoox) Make an extension which a user can easily check the logs.
    @Mock
    private Appender<ILoggingEvent> appender;
    @Captor
    private ArgumentCaptor<ILoggingEvent> eventCaptor;

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

    @ParameterizedTest
    @ArgumentsSource(ContextFutureCallbackArgumentsProvider.class)
    void makeContextAwareCompletableFutureWithDifferentContext(
            BiConsumer<CompletableFuture<?>, AtomicBoolean> callback) {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx1 = ServiceRequestContext.builder(req).build();
        final ServiceRequestContext ctx2 = ServiceRequestContext.builder(req).build();
        try (SafeCloseable ignored = ctx1.push()) {
            final CompletableFuture<Object> future = new CompletableFuture<>();
            final CompletableFuture<Object> contextAwareFuture = ctx2.makeContextAware(future);
            final AtomicBoolean callbackCalled = new AtomicBoolean();
            callback.accept(contextAwareFuture, callbackCalled);

            future.complete(null);

            assertThat(callbackCalled.get()).isFalse();
            verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getMessage()).startsWith("An error occurred while pushing");
            });
        }
    }

    @Test
    void exceptionIfDifferentCtxsAreUsed() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx1 = ServiceRequestContext.builder(req).build();
        final ServiceRequestContext ctx2 = ServiceRequestContext.builder(req).build();
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final CompletableFuture<Object> contextAwareFuture = ctx1.makeContextAware(future);
        assertThatThrownBy(() -> ctx2.makeContextAware(contextAwareFuture))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameFutureIsReturnedIfMakeContextAwareIsCalledTwice() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.builder(req).build();
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final CompletableFuture<Object> contextAwareFuture = ctx.makeContextAware(future);
        assertThat(ctx.makeContextAware(contextAwareFuture)).isSameAs(contextAwareFuture);
    }
}
