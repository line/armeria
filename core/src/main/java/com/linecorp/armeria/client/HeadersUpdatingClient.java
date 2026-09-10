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
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * The {@link HeadersUpdatingClient} enables the modification of request and response headers,
 * allowing modifications before sending requests or after receiving responses.
 *
 * <p>Usage:
 * <pre>{@code
 * HeadersUpdatingClient.builder()
 *     .requestHeaders()
 *     .add(key, "a")
 *     // Modifying request headers asynchronously
 *     .addAsync(key, key -> UnmodifiableFuture.completedFuture(key + "b"))
 *     .add(key, key -> "c") // key eventually becomes "abc"
 *     .and()
 *     .responseHeaders()
 *     .add...
 *     .and()
 *     .newDecorator();
 * }</pre>
 * The builder allows the addition or modification of headers for both request and response
 * in both synchronous and asynchronous manners.
 */
public final class HeadersUpdatingClient extends SimpleDecoratingHttpClient {
    private final Map<CharSequence, Function<String, CompletableFuture<String>>> requestHeaderFunctionMap;
    private final Map<CharSequence, Function<String, CompletableFuture<String>>> responseHeaderFunctionMap;

    HeadersUpdatingClient(
            HttpClient delegate,
            Map<CharSequence, Function<String, CompletableFuture<String>>> requestHeaderFunctionMap,
            Map<CharSequence, Function<String, CompletableFuture<String>>> responseHeaderFunctionMap) {
        super(delegate);
        this.requestHeaderFunctionMap = requestHeaderFunctionMap;
        this.responseHeaderFunctionMap = responseHeaderFunctionMap;
    }

    /**
     * Returns a new {@link HeadersUpdatingClientBuilder}.
     */
    public static HeadersUpdatingClientBuilder builder() {
        return new HeadersUpdatingClientBuilder();
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Map<CharSequence, Function<String, CompletableFuture<String>>> mergedRequestHeaders =
                new HashMap<>(requestHeaderFunctionMap);
        req.headers().forEach(
                (k, v) -> mergedRequestHeaders.put(k, header -> UnmodifiableFuture.completedFuture(v)));

        final List<CompletableFuture<Entry<CharSequence, String>>> reqHeadersListFuture = mergedRequestHeaders
                .entrySet()
                .stream()
                .map(e -> e.getValue()
                           .apply(ctx.defaultRequestHeaders().getLast(e.getKey()))
                           .<Map.Entry<CharSequence, String>>thenApply(
                                   v -> new SimpleImmutableEntry<>(e.getKey(), v)
                           ))
                .collect(Collectors.toList());

        final CompletableFuture<List<Entry<CharSequence, String>>> reqHeadersFutureList = CompletableFuture
                .allOf(reqHeadersListFuture.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> reqHeadersListFuture.stream()
                                                          .map(CompletableFuture::join)
                                                          .collect(Collectors.toList()));

        final HttpResponse resp = HttpResponse.of(
                reqHeadersFutureList
                        .thenApply(headers -> {
                            final HttpRequest decorated = req.withHeaders(
                                    RequestHeaders.builder().add(headers).build());
                            ctx.updateRequest(decorated);
                            try {
                                return unwrap().execute(ctx, decorated);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        }));
        return resp.mapAsyncHeaders(headers -> {
            final Map<CharSequence, Function<String, CompletableFuture<String>>> mergedResponseHeaders =
                    new HashMap<>(responseHeaderFunctionMap);
            headers.forEach((k, v) -> {
                final Function<String, CompletableFuture<String>> existingHeaderFunction =
                        mergedResponseHeaders.getOrDefault(k, CompletableFuture::completedFuture);
                mergedResponseHeaders.put(k, header -> UnmodifiableFuture.completedFuture(v)
                                                                         .thenCompose(existingHeaderFunction));
            });

            final List<CompletableFuture<Entry<CharSequence, String>>> respHeadersList =
                    mergedResponseHeaders
                            .entrySet()
                            .stream()
                            .map(e -> e.getValue()
                                       .apply(null)
                                       .<Map.Entry<CharSequence, String>>thenApply(
                                               v -> new SimpleImmutableEntry<>(e.getKey(), v)
                                       ))
                            .collect(Collectors.toList());

            final CompletableFuture<List<Entry<CharSequence, String>>> respHeadersFutureList = CompletableFuture
                    .allOf(respHeadersList.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> respHeadersList.stream()
                                                         .map(CompletableFuture::join)
                                                         .collect(Collectors.toList()));

            return respHeadersFutureList.thenApply(
                    headerList -> ResponseHeaders.builder().add(headerList).build());
        });
    }
}
