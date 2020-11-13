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

package com.linecorp.armeria.client.retry;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Returns a {@link RetryConfig} given the request context.
 * Allows users to change retry behavior according to any context element, like host, method, path ...etc.
 */
@FunctionalInterface
public interface RetryConfigMapping<T extends Response> {
    /**
     * Creates a {@link KeyedRetryConfigMapping} that maps keys created by {@code keyFactory} to
     * {@link RetryConfig}s created by {@code retryConfigFactory}.
     * The key produced by {@code keyFactory} is used to cache the corresponding {@link RetryConfig} produced
     * by {@code retryConfigFactory}. So if {@code keyFactory} produces a key that has been seen before,
     * the cached {@link RetryConfig} is used, and the output of {@code retryConfigFactory} is ignored.
     * Example:
     * <pre> {@code
     * BiFunction<ClientRequestContext, Request, String> keyFactory =
     *     (ctx, req) -> ctx.endpoint().host();
     * BiFunction<ClientRequestContext, Request, RetryConfig<HttpResponse>> configFactory = (ctx, req) -> {
     *     if (ctx.endpoint().host().equals("host1")) {
     *         return RetryConfig.<HttpResponse>builder(RetryRule.onException()).maxTotalAttempts(2).build();
     *     } else if (ctx.endpoint().host().equals("host2")) {
     *         return RetryConfig.<HttpResponse>builder(RetryRule.onException()).maxTotalAttempts(4).build();
     *     } else {
     *         return RetryConfig.<HttpResponse>builder(RetryRule.onException()).maxTotalAttempts(1).build();
     *     }
     * };
     * RetryConfigMapping mapping = RetryConfigMapping.of(keyFactory, configFactory);
     * } </pre>
     */
    static <T extends Response> RetryConfigMapping<T> of(
            BiFunction<? super ClientRequestContext, Request, String> keyFactory,
            BiFunction<? super ClientRequestContext, Request, RetryConfig<T>> retryConfigFactory) {
        return new KeyedRetryConfigMapping<>(keyFactory, retryConfigFactory);
    }

    /**
     * Returns the {@link RetryConfig} that maps to the given context/request.
     */
    RetryConfig<T> get(ClientRequestContext ctx, Request req);
}
