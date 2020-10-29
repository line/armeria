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

final class KeyedRetryConfigMapping implements RetryConfigMapping {
    private final BiFunction<ClientRequestContext, Request, ? extends RetryConfig> retryConfigFactory;
    private final BiFunction<ClientRequestContext, Request, String> keyFactory;
    private final ConcurrentMap<String, RetryConfig>  mapping = new ConcurrentHashMap<>();

    KeyedRetryConfigMapping(
            BiFunction<ClientRequestContext, Request, String> keyFactory,
            BiFunction<ClientRequestContext, Request, ? extends RetryConfig> retryConfigFactory
    ) {
        this.keyFactory = requireNonNull(keyFactory, "keyFactory");
        this.retryConfigFactory = requireNonNull(retryConfigFactory, "retryConfigFactory");
    }

    @Override
    public RetryConfig get(ClientRequestContext ctx, Request req) throws Exception {
        final String key = keyFactory.apply(ctx, req);
        final RetryConfig config = mapping.get(key);
        if (config != null) {
            return config;
        }
        return mapping.computeIfAbsent(key, mapKey -> retryConfigFactory.apply(ctx, req));
    }
}
