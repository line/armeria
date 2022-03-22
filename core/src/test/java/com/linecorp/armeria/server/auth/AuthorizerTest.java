/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.auth;

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static com.linecorp.armeria.common.util.UnmodifiableFuture.exceptionallyCompletedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

class AuthorizerTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Nullable
    private static ServiceRequestContext serviceCtx;

    @BeforeAll
    static void setServiceContext() {
        serviceCtx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                          .eventLoop(eventLoop.get())
                                          .build();
    }

    @AfterAll
    static void clearServiceContext() {
        serviceCtx = null;
    }

    @Test
    void success() {
        final Authorizer<String> a = (ctx, data) -> completedFuture(true);

        final Boolean result = a.authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result).isTrue();

        final AuthorizationStatus result2 = a.authorizeAndSupplyHandlers(serviceCtx, "data")
                                             .toCompletableFuture().join();
        assertThat(result2.isAuthorized()).isTrue();
        assertThat(result2.successHandler()).isNull();
        assertThat(result2.failureHandler()).isNull();
    }

    @Test
    void failure() {
        final Authorizer<String> a = (ctx, data) -> completedFuture(false);

        final Boolean result = a.authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result).isFalse();

        final AuthorizationStatus result2 = a.authorizeAndSupplyHandlers(serviceCtx, "data")
                                             .toCompletableFuture().join();
        assertThat(result2.isAuthorized()).isFalse();
        assertThat(result2.successHandler()).isNull();
        assertThat(result2.failureHandler()).isNull();
    }

    @Test
    void throwable() {
        final Exception expected = new Exception();
        final Authorizer<String> a = (ctx, data) -> exceptionallyCompletedFuture(expected);

        assertThatThrownBy(() -> a.authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);

        assertThatThrownBy(() -> a.authorizeAndSupplyHandlers(serviceCtx, "data")
                                  .toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);
    }

    @Test
    void nullStatus() {
        final Authorizer<String> a = (ctx, data) -> completedFuture(null);

        assertThat(a.authorize(serviceCtx, "data").toCompletableFuture().join()).isNull();

        assertThat(a.authorizeAndSupplyHandlers(serviceCtx, "data").toCompletableFuture().join()).isNull();
    }

    @Test
    void successWithHandlers() {
        final Authorizer<String> a = new NamedAuthorizer<>("A", true);

        final Boolean result1 = a.authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result1).isTrue();

        final AuthorizationStatus result2 = a.authorizeAndSupplyHandlers(serviceCtx, "data")
                                             .toCompletableFuture().join();
        assertThat(result2.isAuthorized()).isTrue();
        assertThat(result2.successHandler()).isNotNull();
        assertThat(result2.failureHandler()).isNull();
    }

    @Test
    void failureWithHandlers() {
        final Authorizer<String> a = new NamedAuthorizer<>("A", false);

        final Boolean result1 = a.authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result1).isFalse();

        final AuthorizationStatus result2 = a.authorizeAndSupplyHandlers(serviceCtx, "data")
                                             .toCompletableFuture().join();
        assertThat(result2.isAuthorized()).isFalse();
        assertThat(result2.successHandler()).isNull();
        assertThat(result2.failureHandler()).isNotNull();
    }

    @Test
    void throwableWithHandlers() {
        final Exception expected = new Exception();
        final Authorizer<String> a = new NamedAuthorizer<>("A", expected);

        assertThatThrownBy(() -> a.authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);

        assertThatThrownBy(() -> a.authorizeAndSupplyHandlers(serviceCtx, "data")
                                  .toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);
    }

    @Test
    void nullStatusWithHandlers() {
        final Authorizer<String> a = new NullAuthorizer<>();

        assertThat(a.authorize(serviceCtx, "data").toCompletableFuture().join()).isNull();

        assertThat(a.authorizeAndSupplyHandlers(serviceCtx, "data").toCompletableFuture().join()).isNull();
    }

    @Test
    void orElseFirst() {
        final Authorizer<String> a = (ctx, data) -> completedFuture(true);
        final Authorizer<String> b = newMock();

        final Boolean result = a.orElse(b).authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result).isTrue();
        verify(b, never()).authorize(any(), any());
    }

    /**
     * When the first {@link Authorizer} raises an exception, the second {@link Authorizer} shouldn't be
     * invoked.
     */
    @Test
    void orElseFirstException() {
        final Exception expected = new Exception();
        final Authorizer<String> a = (ctx, data) -> exceptionallyCompletedFuture(expected);
        final Authorizer<String> b = newMock();

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);
        verifyNoMoreInteractions(b);
    }

    /**
     * When the first {@link Authorizer} returns a {@link CompletionStage} that fulfills with {@code null},
     * the second {@link Authorizer} shouldn't be invoked.
     */
    @Test
    void orElseFirstNull() {
        final Authorizer<String> a = (ctx, data) -> completedFuture(null);
        final Authorizer<String> b = newMock();

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);
        verifyNoMoreInteractions(b);
    }

    @Test
    void orElseSecond() {
        final Authorizer<String> a = (ctx, data) -> completedFuture(false);
        final Authorizer<String> b = (ctx, data) -> completedFuture(true);

        final Boolean result = a.orElse(b).authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result).isTrue();
    }

    @Test
    void orElseToString() {
        final Authorizer<Object> a = new NamedAuthorizer<>("A");
        final Authorizer<Object> b = new NamedAuthorizer<>("B");
        final Authorizer<Object> c = new NamedAuthorizer<>("C");
        final Authorizer<Object> d = new NamedAuthorizer<>("D");
        final Authorizer<Object> e = new NamedAuthorizer<>("E");

        // A + B
        assertThat(a.orElse(b).toString()).isEqualTo("[A, B]");
        // A + B + C
        assertThat(a.orElse(b).orElse(c).toString()).isEqualTo("[A, B, C]");
        // A + B + (C + D) + E
        assertThat(a.orElse(b).orElse(c.orElse(d)).orElse(e).toString()).isEqualTo("[A, B, C, D, E]");
    }

    @ParameterizedTest
    @MethodSource("orElseHandlerArguments")
    void orElseHandler(boolean[] statuses, int nullHandler, boolean expectedStatus,
                       int expectedSuccessHandler, int expectedFailureHandler) {
        final Authorizer<String>[] authorizers = new Authorizer[statuses.length];
        Authorizer<String> authorizer = null;
        for (int i = 0; i < statuses.length; i++) {
            authorizers[i] = (i == nullHandler) ?
                             new NamedAuthorizer<>(Integer.toString(i), statuses[i], null, null, null)
                             : new NamedAuthorizer<>(Integer.toString(i), statuses[i]);
            authorizer = (i == 0) ? authorizers[i] : authorizer.orElse(authorizers[i]);
        }

        assertThat(authorizer).isNotNull();
        assertThat(serviceCtx).isNotNull();
        final AuthorizationStatus result = authorizer.authorizeAndSupplyHandlers(serviceCtx, "data")
                                                     .toCompletableFuture().join();
        assertThat(result).isNotNull();
        assertThat(result.isAuthorized()).isEqualTo(expectedStatus);
        final AuthSuccessHandler successHandler = result.successHandler();
        final AuthFailureHandler failureHandler = result.failureHandler();
        if (expectedSuccessHandler >= 0) {
            assertThat(successHandler).isNotNull();
            assertThat(successHandler.toString()).isEqualTo(Integer.toString(expectedSuccessHandler));
        } else {
            assertThat(successHandler).isNull();
        }
        if (expectedFailureHandler >= 0) {
            assertThat(failureHandler).isNotNull();
            assertThat(failureHandler.toString()).isEqualTo(Integer.toString(expectedFailureHandler));
        } else {
            assertThat(failureHandler).isNull();
        }
    }

    private static Stream<Arguments> orElseHandlerArguments() {
        return Stream.of(
          Arguments.of(new boolean[] {true, true, true}, -1, true, 0, -1),
          Arguments.of(new boolean[] {true, true, false}, -1, true, 0, -1),
          Arguments.of(new boolean[] {true, false, true}, -1, true, 0, -1),
          Arguments.of(new boolean[] {true, false, false}, -1, true, 0, -1),

          Arguments.of(new boolean[] {false, true, true}, -1, true, 1, -1),
          Arguments.of(new boolean[] {false, true, false}, -1, true, 1, -1),
          Arguments.of(new boolean[] {false, false, true}, -1, true, 2, -1),
          Arguments.of(new boolean[] {false, false, false}, -1, false, -1, 2),

          Arguments.of(new boolean[] {true, true, true}, 0, true, -1, -1),
          Arguments.of(new boolean[] {false, true, true}, 1, true, -1, -1),
          Arguments.of(new boolean[] {false, false, true}, 2, true, -1, -1),

          Arguments.of(new boolean[] {false, false, false}, 0, false, -1, 2),
          Arguments.of(new boolean[] {false, false, false}, 1, false, -1, 2),
          Arguments.of(new boolean[] {false, false, false}, 2, false, -1, 1)
        );
    }

    private static Authorizer<String> newMock() {
        @SuppressWarnings("unchecked")
        final Authorizer<String> mock = mock(Authorizer.class);
        lenient().when(mock.orElse(any())).thenCallRealMethod();
        return mock;
    }

    private static class NamedAuthorizer<T> extends AbstractAuthorizerWithHandlers<T> {

        private final String name;
        private final boolean authorize;
        @Nullable
        private final Throwable cause;
        @Nullable
        private final AuthSuccessHandler successHandler;
        @Nullable
        private final AuthFailureHandler failureHandler;

        private NamedAuthorizer(String name, boolean authorize, @Nullable Throwable cause,
                                @Nullable AuthSuccessHandler successHandler,
                                @Nullable AuthFailureHandler failureHandler) {
            this.name = name;
            this.authorize = authorize;
            this.cause = cause;
            this.successHandler = successHandler;
            this.failureHandler = failureHandler;
        }

        NamedAuthorizer(String name, boolean authorize) {
            this(name, authorize, null, new NamedSuccessHandler(name), new NamedFailureHandler(name));
        }

        NamedAuthorizer(String name, Throwable cause) {
            this(name, true, cause, new NamedSuccessHandler(name), new NamedFailureHandler(name));
        }

        NamedAuthorizer(String name) {
            this(name, true);
        }

        @Override
        public CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(ServiceRequestContext ctx,
                                                                               @Nullable T data) {
            if (cause != null) {
                return exceptionallyCompletedFuture(cause);
            }
            return completedFuture(authorize ? AuthorizationStatus.ofSuccess(successHandler)
                                             : AuthorizationStatus.ofFailure(failureHandler));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class NullAuthorizer<T> extends AbstractAuthorizerWithHandlers<T> {

        @Override
        public CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(ServiceRequestContext ctx,
                                                                               @Nullable T data) {
            return completedFuture(null);
        }
    }

    private static final class NamedSuccessHandler implements AuthSuccessHandler {

        private final String name;

        NamedSuccessHandler(String name) {
            this.name = name;
        }

        @Override
        public HttpResponse authSucceeded(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return delegate.serve(ctx, req);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class NamedFailureHandler implements AuthFailureHandler {

        private final String name;

        NamedFailureHandler(String name) {
            this.name = name;
        }

        @Override
        public HttpResponse authFailed(HttpService delegate, ServiceRequestContext ctx, HttpRequest req,
                                       @Nullable Throwable cause) throws Exception {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
