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
 */
@FunctionalInterface
public interface RetryConfigMapping<T extends Response> {
    /**
     * Creates a {@link KeyedRetryConfigMapping} that maps keys created by keyFactory to  {@link RetryConfig}s
     * created by retryConfigFactory.
     */
    static <T extends Response> RetryConfigMapping<T> of(
            BiFunction<? super ClientRequestContext, Request, String> keyFactory,
            BiFunction<? super ClientRequestContext, Request, RetryConfig<T>> retryConfigFactory) {
        return new KeyedRetryConfigMapping<>(keyFactory, retryConfigFactory);
    }

    /**
     * Returns tha {@link RetryConfig} that maps to the given context/request.
     */
    RetryConfig<T> get(ClientRequestContext ctx, Request req);
}
