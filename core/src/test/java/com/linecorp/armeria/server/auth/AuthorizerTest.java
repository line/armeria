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

import static com.google.common.base.Preconditions.checkState;
import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

public class AuthorizerTest {

    @Nullable
    private static EventLoop eventLoop;
    @Nullable
    private static ServiceRequestContext serviceCtx;

    @BeforeClass
    public static void setEventLoop() {
        eventLoop = new DefaultEventLoop();
        serviceCtx = mock(ServiceRequestContext.class);
        when(serviceCtx.contextAwareEventLoop()).thenReturn(eventLoop);
    }

    @AfterClass
    public static void clearEventLoop() {
        serviceCtx = null;
        if (eventLoop != null) {
            eventLoop.shutdownGracefully();
            eventLoop = null;
        }
    }

    @Test
    public void orElseFirst() {
        final AtomicBoolean executedA = new AtomicBoolean();
        final Authorizer<Object> a = (ctx, data) -> {
            checkState(executedA.compareAndSet(false, true),
                       "The first authorizer was invoked more than once.");
            return completedFuture(true);
        };
        final Authorizer<Object> b = (ctx, data) -> exceptionallyCompletedFuture(new AssertionError());

        final Boolean result = a.orElse(b).authorize(serviceCtx, new Object())
                                .toCompletableFuture().join();
        assertThat(result).isTrue();
        assertThat(executedA).isTrue();
    }

    /**
     * When the first {@link Authorizer} raises an exception, the second {@link Authorizer} shouldn't be
     * invoked.
     */
    @Test
    public void orElseFirstException() {
        final Exception expected = new Exception();
        final Authorizer<Object> a = (ctx, data) -> exceptionallyCompletedFuture(expected);
        final Authorizer<Object> b = (ctx, data) -> exceptionallyCompletedFuture(new AssertionError());

        assertThatThrownBy(() -> a.orElse(b).authorize(serviceCtx, new Object()).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(expected);
    }

    @Test
    public void orElseSecond() {
        final AtomicBoolean executedA = new AtomicBoolean();
        final Authorizer<Object> a = (ctx, data) -> {
            checkState(executedA.compareAndSet(false, true),
                       "The first authorizer was invoked more than once.");
            return completedFuture(false);
        };
        final Authorizer<Object> b = (ctx, data) -> completedFuture(true);

        final Boolean result = a.orElse(b).authorize(serviceCtx, new Object())
                                .toCompletableFuture().join();
        assertThat(result).isTrue();
        assertThat(executedA).isTrue();
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
