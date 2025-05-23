/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.hedging;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

final class KeyedHedgingConfigMapping<T extends Response> implements HedgingConfigMapping<T> {
    private final BiFunction<? super ClientRequestContext, Request, HedgingConfig<T>> hedgingConfigFactory;
    private final BiFunction<? super ClientRequestContext, Request, String> keyFactory;

    private final ConcurrentMap<String, HedgingConfig<T>> mapping = new ConcurrentHashMap<>();

    KeyedHedgingConfigMapping(
            BiFunction<? super ClientRequestContext, Request, String> keyFactory,
            BiFunction<? super ClientRequestContext, Request, HedgingConfig<T>> hedgingConfigFactory) {
        this.keyFactory = requireNonNull(keyFactory, "keyFactory");
        this.hedgingConfigFactory = requireNonNull(hedgingConfigFactory, "hedgingConfigFactory");
    }

    @Override
    public HedgingConfig<T> get(ClientRequestContext ctx, Request req) {
        final String key = keyFactory.apply(ctx, req);
        requireNonNull(key, "keyFactory.apply() returned null");
        return mapping.computeIfAbsent(key, mapKey -> {
            final HedgingConfig<T> retryConfig = hedgingConfigFactory.apply(ctx, req);
            requireNonNull(retryConfig, "hedgingConfigFactory.apply() returned null");
            return retryConfig;
        });
    }
}
