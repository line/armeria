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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

final class KeyedRetryConfigMapping<T extends Response> implements RetryConfigMapping<T> {
    private final BiFunction<? super ClientRequestContext, Request, RetryConfig<T>> retryConfigFactory;
    private final BiFunction<? super ClientRequestContext, Request, String> keyFactory;
    private final ConcurrentMap<String, RetryConfig<T>> mapping = new ConcurrentHashMap<>();

    KeyedRetryConfigMapping(
            BiFunction<? super ClientRequestContext, Request, String> keyFactory,
            BiFunction<? super ClientRequestContext, Request, RetryConfig<T>> retryConfigFactory) {
        this.keyFactory = requireNonNull(keyFactory, "keyFactory");
        this.retryConfigFactory = requireNonNull(retryConfigFactory, "retryConfigFactory");
    }

    @Override
    public RetryConfig<T> get(ClientRequestContext ctx, Request req) {
        final String key = keyFactory.apply(ctx, req);
        requireNonNull(key, "keyFactory.apply() returned null");
        return mapping.computeIfAbsent(key, mapKey -> {
            final RetryConfig<T> retryConfig = retryConfigFactory.apply(ctx, req);
            requireNonNull(retryConfig, "retryConfigFactory.apply() returned null");
            return retryConfig;
        });
    }
}
