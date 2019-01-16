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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;
import com.linecorp.armeria.testing.common.EventLoopRule;

public class AuthorizerTest {

    @ClassRule
    public static final EventLoopRule eventLoop = new EventLoopRule();

    @Nullable
    private static ServiceRequestContext serviceCtx;

    @BeforeClass
    public static void setServiceContext() {
        serviceCtx = ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/"))
                                                 .eventLoop(eventLoop.get())
                                                 .build();
    }

    @AfterClass
    public static void clearServiceContext() {
        serviceCtx = null;
    }

    @Test
    public void orElseFirst() {
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
    public void orElseFirstException() {
        final Exception expected = new Exception();
        final Authorizer<String> a = newMock();
        when(a.authorize(any(), any())).thenReturn(exceptionallyCompletedFuture(expected));
        final Authorizer<String> b = newMock();

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);
        verify(a, times(1)).authorize(serviceCtx, "data");
        verifyZeroInteractions(b);
    }

    /**
     * When the first {@link Authorizer} returns a {@link CompletionStage} that fulfills with {@code null},
     * the second {@link Authorizer} shouldn't be invoked.
     */
    @Test
    public void orElseFirstNull() {
        final Authorizer<String> a = newMock();
        when(a.authorize(any(), any())).thenReturn(completedFuture(null));
        final Authorizer<String> b = newMock();

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, "data").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);
        verify(a, times(1)).authorize(serviceCtx, "data");
        verifyZeroInteractions(b);
    }

    @Test
    public void orElseSecond() {
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
    public void orElseToString() {
        final Authorizer<Object> a = new AuthorizerWithToString("A");
        final Authorizer<Object> b = new AuthorizerWithToString("B");
        final Authorizer<Object> c = new AuthorizerWithToString("C");
        final Authorizer<Object> d = new AuthorizerWithToString("D");

        // A + B
        assertThat(a.orElse(b).toString()).isEqualTo("[A, B]");
        // A + B
        assertThat(a.orElse(b).orElse(c).toString()).isEqualTo("[A, B, C]");
        // (A + B) + (C + D)
        assertThat(a.orElse(b).orElse(c.orElse(d)).toString()).isEqualTo("[A, B, C, D]");
    }

    private static Authorizer<String> newMock() {
        @SuppressWarnings("unchecked")
        final Authorizer<String> mock = mock(Authorizer.class);
        when(mock.orElse(any())).thenCallRealMethod();
        return mock;
    }

    private static final class AuthorizerWithToString implements Authorizer<Object> {

        private final String strVal;

        AuthorizerWithToString(String strVal) {
            this.strVal = strVal;
        }

        @Override
        public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, Object data) {
            return completedFuture(true);
        }

        @Override
        public String toString() {
            return strVal;
        }
    }
}
