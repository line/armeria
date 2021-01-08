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

import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import javax.annotation.Nullable;

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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

public class AuthorizerTest {

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
    void orElseFirst() {
        final Authorizer<String> a = newMock();
        when(a.authorize(any(), any())).thenReturn(completedFuture(true));
        final Authorizer<String> b = newMock();

        final Boolean result = a.orElse(b).authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result).isTrue();
        verify(a, times(1)).authorize(serviceCtx, "data");
        verify(b, never()).authorize(any(), any());
    }

    /**
     * When the first {@link Authorizer} raises an exception, the second {@link Authorizer} shouldn't be
     * invoked.
     */
    @Test
    void orElseFirstException() {
        final Exception expected = new Exception();
        final Authorizer<String> a = newMock();
        when(a.authorize(any(), any())).thenReturn(exceptionallyCompletedFuture(expected));
        final Authorizer<String> b = newMock();

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);
        verify(a, times(1)).authorize(serviceCtx, "data");
        verifyNoMoreInteractions(b);
    }

    /**
     * When the first {@link Authorizer} returns a {@link CompletionStage} that fulfills with {@code null},
     * the second {@link Authorizer} shouldn't be invoked.
     */
    @Test
    void orElseFirstNull() {
        final Authorizer<String> a = newMock();
        when(a.authorize(any(), any())).thenReturn(completedFuture(null));
        final Authorizer<String> b = newMock();

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);
        verify(a, times(1)).authorize(serviceCtx, "data");
        verifyNoMoreInteractions(b);
    }

    @Test
    void orElseSecond() {
        final Authorizer<String> a = newMock();
        when(a.authorize(any(), any())).thenReturn(completedFuture(false));
        final Authorizer<String> b = newMock();
        when(b.authorize(any(), any())).thenReturn(completedFuture(true));

        final Boolean result = a.orElse(b).authorize(serviceCtx, "data")
                                .toCompletableFuture().join();
        assertThat(result).isTrue();
        verify(a, times(1)).authorize(serviceCtx, "data");
        verify(b, times(1)).authorize(serviceCtx, "data");
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
        assertThat(a.orElse(b).orElse(c.orElse(d)).orElse(e).toString()).isEqualTo("[A, B, [C, D], E]");
    }

    @ParameterizedTest
    @MethodSource("orElseHandlerArguments")
    void orElseHandler(boolean[] statuses, int nullHandler, boolean expectedStatus,
                       int expectedSuccessHandler, int expectedFailureHandler) {
        final Authorizer<String>[] authorizers = new Authorizer[statuses.length];
        Authorizer<String> authorizer = null;
        for (int i = 0; i < statuses.length; i++) {
            authorizers[i] = (i == nullHandler) ?
                             new NamedAuthorizer<>(Integer.toString(i), statuses[i], null, null)
                             : new NamedAuthorizer<>(Integer.toString(i), statuses[i]);
            authorizer = (i == 0) ? authorizers[i] : authorizer.orElse(authorizers[i]);
        }

        assertThat(authorizer).isNotNull();
        assertThat(serviceCtx).isNotNull();
        final Boolean result = authorizer.authorize(serviceCtx, "data")
                                         .toCompletableFuture().join();
        final AuthSuccessHandler successHandler = authorizer.successHandler();
        final AuthFailureHandler failureHandler = authorizer.failureHandler();
        assertThat(result).isEqualTo(expectedStatus);
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

          Arguments.of(new boolean[] {false, true, true}, -1, true, 1, 0),
          Arguments.of(new boolean[] {false, true, false}, -1, true, 1, 0),
          Arguments.of(new boolean[] {false, false, true}, -1, true, 2, 1),
          Arguments.of(new boolean[] {false, false, false}, -1, false, -1, 2),

          Arguments.of(new boolean[] {true, true, true}, 0, true, -1, -1),
          Arguments.of(new boolean[] {false, true, true}, 1, true, -1, 0),
          Arguments.of(new boolean[] {false, false, true}, 2, true, -1, 1),

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

    private static final class NamedAuthorizer<T> implements Authorizer<T> {

        private final String name;
        private final boolean authorize;
        @Nullable
        private final AuthSuccessHandler successHandler;
        @Nullable
        private final AuthFailureHandler failureHandler;

        NamedAuthorizer(String name, boolean authorize,
                        @Nullable AuthSuccessHandler successHandler,
                        @Nullable AuthFailureHandler failureHandler) {
            this.name = name;
            this.authorize = authorize;
            this.successHandler = successHandler;
            this.failureHandler = failureHandler;
        }

        NamedAuthorizer(String name, boolean authorize) {
            this(name, authorize, new NamedSuccessHandler(name), new NamedFailureHandler(name));
        }

        NamedAuthorizer(String name) {
            this(name, true);
        }

        @Override
        public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, T data) {
            return completedFuture(authorize);
        }

        @Nullable
        @Override
        public AuthSuccessHandler successHandler() {
            return successHandler;
        }

        @Nullable
        @Override
        public AuthFailureHandler failureHandler() {
            return failureHandler;
        }

        @Override
        public String toString() {
            return name;
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
