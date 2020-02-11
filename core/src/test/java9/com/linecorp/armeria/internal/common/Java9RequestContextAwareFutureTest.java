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
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.server.ServiceRequestContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

class Java9RequestContextAwareFutureTest {

    // TODO(minwoox) Make an extesion which a user can easily check the logs.
    @Mock
    private Appender<ILoggingEvent> appender;
    @Captor
    private ArgumentCaptor<ILoggingEvent> eventCaptor;

    @BeforeAll
    static void checkEnv() {
        assumeThat(SystemInfo.javaVersion()).isGreaterThanOrEqualTo(9);
    }

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
    void minimalCompletionStageUsingToCompletableFutureMutable() throws Exception {
        final RequestContext context =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Integer> originalFuture = new CompletableFuture<>();
        final CompletableFuture<Integer> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletionStage<Integer> completionStage = contextAwareFuture.minimalCompletionStage();

        assertThat(contextAwareFuture.complete(1)).isTrue();
        assertThat(contextAwareFuture.join()).isEqualTo(1);
        assertThat(contextAwareFuture.getNow(null)).isEqualTo(1);
        assertThat(contextAwareFuture.get()).isEqualTo(1);
        assertThat(completionStage.toCompletableFuture().get()).isEqualTo(1);
    }

    @Test
    void minimalCompletionStageUsingWhenComplete() throws Exception {
        final RequestContext context =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Integer> originalFuture = new CompletableFuture<>();
        final CompletableFuture<Integer> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletionStage<Integer> completionStage = contextAwareFuture.minimalCompletionStage();

        final AtomicInteger atomicInteger = new AtomicInteger();
        final AtomicReference<Throwable> causeCaptor = new AtomicReference<>();
        completionStage.whenComplete((result, error) -> {
            if (error != null) {
                causeCaptor.set(error);
            } else {
                atomicInteger.set(result);
            }
        });
        contextAwareFuture.complete(1);

        assertThat(contextAwareFuture.join()).isEqualTo(1);
        assertThat(contextAwareFuture.getNow(null)).isEqualTo(1);
        assertThat(contextAwareFuture.get()).isEqualTo(1);
        assertThat(atomicInteger.get()).isEqualTo(1);
        assertThat(causeCaptor.get()).isNull();
    }

    @Test
    void makeContextAwareCompletableFutureUsingCompleteAsync() throws Exception {
        final RequestContext context =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<String> originalFuture = new CompletableFuture<>();
        final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletableFuture<String> resultFuture = contextAwareFuture.completeAsync(() -> "success");

        originalFuture.complete("success");
        assertThat(resultFuture.get()).isEqualTo("success");
    }

    @Test
    void makeContextAwareCompletableFutureUsingCompleteAsyncWithExecutor() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final RequestContext context =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<String> originalFuture = new CompletableFuture<>();
        final CompletableFuture<String> contextAwareFuture = context.makeContextAware(originalFuture);
        final CompletableFuture<String> resultFuture = contextAwareFuture.completeAsync(() -> "success",
                                                                                        executor);

        originalFuture.complete("success");
        assertThat(resultFuture.get()).isEqualTo("success");
    }

    @ParameterizedTest
    @ArgumentsSource(Java9CallbackArgumentsProvider.class)
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
                assertThat(event.getMessage()).startsWith("An error occurred when pushing");
            });
        }
    }

    private static final class Java9CallbackArgumentsProvider extends ContextFutureCallbackArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Arguments completeAsync = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                        future.completeAsync(() -> {
                            called.set(true);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                            called.set(true);
                            return null;
                        });
                    });

            return Stream.concat(super.provideArguments(context), Stream.of(completeAsync));
        }
    }
}
