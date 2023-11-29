/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;

public final class HeaderFunctionMap {
    private final HeadersUpdatingClientBuilder headersUpdatingClientBuilder;
    private final Map<CharSequence, Function<String, CompletableFuture<String>>> functionMap = new HashMap<>();

    HeaderFunctionMap(HeadersUpdatingClientBuilder headersUpdatingClientBuilder) {
        this.headersUpdatingClientBuilder = requireNonNull(headersUpdatingClientBuilder,
                                                           "headersUpdatingClientBuilder");
    }

    public HeaderFunctionMap add(CharSequence name, @Nullable String value) {
        functionMap.put(name, header -> CompletableFuture.completedFuture(value));
        return this;
    }

    public HeaderFunctionMap add(CharSequence name, Function<@Nullable String, String> function) {
        final Function<String, CompletableFuture<String>> f =
                functionMap.getOrDefault(name, CompletableFuture::completedFuture);
        functionMap.put(name, header -> f.apply(header).thenApply(function));
        return this;
    }

    public HeaderFunctionMap addAsync(CharSequence name,
                                      Function<@Nullable String, CompletableFuture<String>> function) {
        final Function<String, CompletableFuture<String>> f =
                functionMap.getOrDefault(name, CompletableFuture::completedFuture);
        functionMap.put(name, header -> f.apply(header).thenCompose(function));
        return this;
    }

    public HeadersUpdatingClientBuilder and() {
        return headersUpdatingClientBuilder;
    }

    public Map<CharSequence, Function<String, CompletableFuture<String>>> functionMap() {
        return functionMap;
    }
}
