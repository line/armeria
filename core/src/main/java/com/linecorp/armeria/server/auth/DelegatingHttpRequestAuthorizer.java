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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Authorizes {@link HttpRequest}s utilizing provided Token Extractor {@link Function} and delegating
 * extracted Token authorization to a designated {@link Authorizer}, in order to be used by
 * {@link AuthServiceBuilder}.
 * @param <T> token type
 */
final class DelegatingHttpRequestAuthorizer<T> implements Authorizer<HttpRequest> {

    private final Function<? super RequestHeaders, T> tokenExtractor;
    private final Authorizer<? super T> delegate;

    DelegatingHttpRequestAuthorizer(Function<? super RequestHeaders, T> tokenExtractor,
                                    Authorizer<? super T> delegate) {
        this.tokenExtractor = requireNonNull(tokenExtractor, "tokenExtractor");
        this.delegate = requireNonNull(delegate, "authorizer");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest request) {
        final T token = tokenExtractor.apply(request.headers());
        if (token == null) {
            return CompletableFuture.completedFuture(false);
        }
        return delegate.authorize(ctx, token);
    }

    @Nullable
    @Override
    public AuthSuccessHandler successHandler() {
        return delegate.successHandler();
    }

    @Nullable
    @Override
    public AuthFailureHandler failureHandler() {
        return delegate.failureHandler();
    }
}
