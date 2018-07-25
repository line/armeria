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

import java.util.concurrent.CompletionStage;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.server.ServiceRequestContext;

final class AuthorizerUtil {

    /**
     * Determines if the specified {@code data} is authorized for this service. If the result resolves
     * to {@code true}, the request is authorized, or {@code false} otherwise. If the future resolves
     * exceptionally, the request will not be authorized.
     */
    static <T> CompletionStage<Boolean> authorize(Authorizer<T> authorizer, ServiceRequestContext ctx, T data) {
        try {
            final CompletionStage<Boolean> f = authorizer.authorize(ctx, data);
            if (f == null) {
                throw new NullPointerException("An " + Authorizer.class.getSimpleName() +
                                               " returned null: " + authorizer);
            }
            return f;
        } catch (Throwable cause) {
            return CompletableFutures.exceptionallyCompletedFuture(cause);
        }
    }

    static NullPointerException newNullResultException(Authorizer<?> authorizer) {
        return new NullPointerException("A future returned by an " + Authorizer.class.getSimpleName() +
                                        " has been fulfilled with null: " + authorizer);
    }

    private AuthorizerUtil() {}
}
