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
 * under the License.
 */

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.ContextFutureCallbackArgumentsProvider.CallbackResult;
import com.linecorp.armeria.server.ServiceRequestContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

class Java12ContextAwareFutureTest {

    // TODO(minwoox) Make an extension which a user can easily check the logs.
    @Mock
    private Appender<ILoggingEvent> appender;
    @Captor
    private ArgumentCaptor<ILoggingEvent> eventCaptor;

    @BeforeAll
    static void checkEnv() {
        assumeThat(SystemInfo.javaVersion()).isGreaterThanOrEqualTo(12);
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

    @ParameterizedTest
    @ArgumentsSource(Java12CallbackArgumentsProvider.class)
    void makeContextAwareCompletableFutureShouldPropagateContext(
            BiConsumer<CompletableFuture<?>, CallbackResult> callback) {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx1 = ServiceRequestContext.builder(req).build();
        try (SafeCloseable ignored = ctx1.push()) {
            final CompletableFuture<Object> future = new CompletableFuture<>();
            final CompletableFuture<Object> contextAwareFuture = ctx1.makeContextAware(future);
            final CallbackResult callbackCalled = new CallbackResult();
            callback.accept(contextAwareFuture, callbackCalled);

            future.complete("");

            assertThat(callbackCalled.called.get()).isTrue();
            verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
            await().untilAsserted(() -> {
                assertThat(eventCaptor.getAllValues()).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getMessage()).startsWith("Router created for");
                });
            });

            assertThat(callbackCalled.context.get()).isEqualTo(ctx1);
        }
    }

    private static final class Java12CallbackArgumentsProvider extends ContextFutureCallbackArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Arguments completeAsync = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, called) -> {
                        future.completeAsync(() -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                            fn().apply(called);
                            return null;
                        });
                    });

            final Arguments exceptionallyAsync = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, called) -> {
                        future.completeAsync(() -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionallyAsync(cause -> {
                            fn().apply(called);
                            return null;
                        });
                    });

            final Arguments exceptionallyAsyncExe = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, called) -> {
                        future.completeAsync(() -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionallyAsync(cause -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor());
                    });

            final Arguments exceptionallyCompose = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, called) -> {
                        future.completeAsync(() -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionallyCompose(cause -> {
                            fn().apply(called);
                            return null;
                        });
                    });

            final Arguments exceptionallyComposeAsync = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, called) -> {
                        future.completeAsync(() -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionallyComposeAsync(cause -> {
                            fn().apply(called);
                            return null;
                        });
                    });

            final Arguments exceptionallyComposeAsyncExe = Arguments.of(
                    (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, called) -> {
                        future.completeAsync(() -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor()).exceptionallyComposeAsync(cause -> {
                            fn().apply(called);
                            return null;
                        }, MoreExecutors.directExecutor());
                    });

            final Stream<Arguments> s = Stream.of(completeAsync,
                                                  exceptionallyAsync,
                                                  exceptionallyAsyncExe,
                                                  exceptionallyCompose,
                                                  exceptionallyComposeAsync,
                                                  exceptionallyComposeAsyncExe);
            return Stream.concat(super.provideArguments(context), s);
        }
    }
}
