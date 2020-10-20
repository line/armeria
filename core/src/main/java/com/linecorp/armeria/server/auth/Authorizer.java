/*
 * Copyright 2020 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Determines whether a given {@code data} is authorized for the service registered in.
 * {@code ctx} can be used for storing authorization information about the request for use in
 * business logic. {@code data} is usually an {@link HttpRequest}
 * or token extracted from it.
 */
@FunctionalInterface
public interface Authorizer<T> {
    /**
     * Authorizes the given {@code data}.
     *
     * @return a {@link CompletionStage} that will resolve to {@code true} if the request is
     *     authorized, or {@code false} otherwise. If the future resolves exceptionally, the request
     *     will not be authorized.
     */
    CompletionStage<Boolean> authorize(ServiceRequestContext ctx, T data);

    /**
     * Returns a new {@link Authorizer} that delegates the authorization request to the specified
     * {@link Authorizer} if this {@link Authorizer} rejects the authorization request by returning
     * a {@link CompletionStage} completed with {@code false}.
     */
    default Authorizer<T> orElse(Authorizer<T> nextAuthorizer) {
        final Authorizer<T> self = this;
        return new Authorizer<T>() {
            @Nullable
            private String strVal;

            @Override
            public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, T data) {
                return AuthorizerUtil.authorize(self, ctx, data).thenComposeAsync(result -> {
                    if (result == null) {
                        throw AuthorizerUtil.newNullResultException(self);
                    } else {
                        return result ? CompletableFuture.completedFuture(true)
                                      : AuthorizerUtil.authorize(nextAuthorizer, ctx, data);
                    }
                }, ctx.eventLoop());
            }

            @Override
            public String toString() {
                if (strVal != null) {
                    return strVal;
                }

                final StringBuilder buf = new StringBuilder();

                // Append the first authorizer.
                if (self.getClass() == getClass()) {
                    final String s = self.toString();
                    buf.append(s, 0, s.length() - 1);
                } else {
                    buf.append('[').append(self);
                }
                buf.append(", ");

                // Append the second authorizer.
                if (nextAuthorizer.getClass() == getClass()) {
                    final String s = nextAuthorizer.toString();
                    buf.append(s, 1, s.length());
                } else {
                    buf.append(nextAuthorizer).append(']');
                }

                return strVal = buf.toString();
            }
        };
    }

    /**
     * Returns the {@link AuthSuccessHandler} which handles successfully authorized requests.
     * By default, returns {@code null}, which means to use the default or whatever specified by
     * the {@link AuthServiceBuilder}.
     * @return An instance of {@link AuthSuccessHandler} to handle successfully authorized requests
     *         or {@code null} to use the default.
     */
    @Nullable
    default AuthSuccessHandler successHandler() {
        return null;
    }

    /**
     * Returns the {@link AuthFailureHandler} which handles the requests with failed authorization.
     * By default, returns {@code null}, which means to use the default or whatever specified by
     * the {@link AuthServiceBuilder}.
     * @return An instance of {@link AuthFailureHandler} to handle the requests with failed authorization
     *         or {@code null} to use the default.
     */
    @Nullable
    default AuthFailureHandler failureHandler() {
        return null;
    }
}
