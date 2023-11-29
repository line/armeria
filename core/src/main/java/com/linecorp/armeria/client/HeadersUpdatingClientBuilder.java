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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class HeadersUpdatingClientBuilder {

    private final List<HeaderFunctionMap> requestHeaders = new ArrayList<>();
    private final List<HeaderFunctionMap> responseHeaders = new ArrayList<>();

    HeadersUpdatingClientBuilder() {}

    public HeaderFunctionMap requestHeaders() {
        final HeaderFunctionMap headerFunctionMap = new HeaderFunctionMap(this);
        requestHeaders.add(headerFunctionMap);
        return headerFunctionMap;
    }

    public HeaderFunctionMap responseHeaders() {
        final HeaderFunctionMap headerFunctionMap = new HeaderFunctionMap(this);
        responseHeaders.add(headerFunctionMap);
        return headerFunctionMap;
    }

    public HeadersUpdatingClient build(HttpClient delegate) {
        return new HeadersUpdatingClient(
                delegate,
                requestHeaders.stream()
                              .map(HeaderFunctionMap::functionMap)
                              .reduce(HeadersUpdatingClientBuilder::mergeHeaderFunctionMap)
                              .orElseGet(HashMap::new),
                responseHeaders.stream()
                               .map(HeaderFunctionMap::functionMap)
                               .reduce(HeadersUpdatingClientBuilder::mergeHeaderFunctionMap)
                               .orElseGet(HashMap::new));
    }

    private static Map<CharSequence, Function<String, CompletableFuture<String>>> mergeHeaderFunctionMap(
            Map<CharSequence, Function<String, CompletableFuture<String>>> map1,
            Map<CharSequence, Function<String, CompletableFuture<String>>> map2
    ) {
        final Map<CharSequence, Function<String, CompletableFuture<String>>> merged =
                new HashMap<>(map1);
        map2.forEach((k, v) -> merged.merge(k, v, (f1, f2) -> header -> f1.apply(header).thenCompose(f2)));
        return merged;
    }

    public Function<? super HttpClient, HeadersUpdatingClient> newDecorator() {
        return this::build;
    }
}
