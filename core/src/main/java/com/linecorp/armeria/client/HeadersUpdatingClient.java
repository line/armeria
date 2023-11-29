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

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;

public final class HeadersUpdatingClient extends SimpleDecoratingHttpClient {
    private final Map<CharSequence, Function<String, CompletableFuture<String>>> requestHeaderFunctionMap;
    private final Map<CharSequence, Function<String, CompletableFuture<String>>> responseHeaderFunctionMap;

    HeadersUpdatingClient(HttpClient delegate,
                          Map<CharSequence, Function<String, CompletableFuture<String>>> requestHeaderFunctionMap,
                          Map<CharSequence, Function<String, CompletableFuture<String>>> responseHeaderFunctionMap) {
        super(delegate);
        this.requestHeaderFunctionMap = requestHeaderFunctionMap;
        this.responseHeaderFunctionMap = responseHeaderFunctionMap;
    }

    public static HeadersUpdatingClientBuilder builder() {
        return new HeadersUpdatingClientBuilder();
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Map<CharSequence, Function<String, CompletableFuture<String>>> merged =
                new HashMap<>(requestHeaderFunctionMap);
        req.headers().forEach((k, v) -> {
            // TODO 우선 순위 고려하기 req.headers(), requestHeaderFunctionMap 중 누가 우선인지, req.headers() 안에 정적 헤더가 있나? => no Client
            merged.put(k, header -> CompletableFuture.completedFuture(v));
        });

        final List<CompletableFuture<Entry<CharSequence, String>>> headerList =
                merged.entrySet()
                                        .stream()
                                        .map(e -> e.getValue().apply(ctx.defaultRequestHeaders().getLast(e.getKey()))
                                                   .<Map.Entry<CharSequence, String>>thenApply(
                                                           v -> new SimpleImmutableEntry<>(e.getKey(), v)))
                                        .collect(Collectors.toList());
        return HttpResponse.of(
                CompletableFuture.allOf(headerList.toArray(new CompletableFuture[0]))
                                 .thenApply(ignored -> headerList.stream()
                                                                 .map(CompletableFuture::join)
                                                                 .collect(Collectors.toList()))
                                 .thenApply(headers -> {
                                     final HttpRequest decorated = req.withHeaders(
                                             RequestHeaders.builder().add(headers).build());
                                     ctx.updateRequest(decorated);
                                     try {
                                         return unwrap().execute(ctx, decorated);
                                     } catch (Exception e) {
                                         throw new CompletionException(e);
                                     }
                                 })
        );
    }
}
