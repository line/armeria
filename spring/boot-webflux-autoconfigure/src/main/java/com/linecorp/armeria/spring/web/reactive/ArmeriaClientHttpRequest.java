/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.AbstractClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequest;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

import io.netty.util.AsciiString;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@link ClientHttpRequest} implementation for the Armeria HTTP client.
 */
final class ArmeriaClientHttpRequest extends AbstractClientHttpRequest {

    private final HttpClient client;

    private final HttpHeaders headers;
    private final DataBufferFactoryWrapper<?> factoryWrapper;

    private final HttpMethod httpMethod;

    private final URI uri;

    private final CompletableFuture<HttpResponse> future = new CompletableFuture<>();

    @Nullable
    private HttpRequest request;

    ArmeriaClientHttpRequest(HttpClient client, HttpMethod httpMethod, String pathAndQuery,
                             URI uri, DataBufferFactoryWrapper<?> factoryWrapper) {
        this.client = requireNonNull(client, "client");
        this.httpMethod = requireNonNull(httpMethod, "httpMethod");
        this.uri = requireNonNull(uri, "uri");
        this.factoryWrapper = requireNonNull(factoryWrapper, "factoryWrapper");
        headers = HttpHeaders.of(com.linecorp.armeria.common.HttpMethod.valueOf(httpMethod.name()),
                                 requireNonNull(pathAndQuery, "pathAndQuery"));
    }

    @Override
    protected void applyHeaders() {
        // Copy the HTTP headers which were specified by a user to the Armeria request.
        getHeaders().forEach((name, values) -> headers.set(AsciiString.of(name), values));
        setDefaultRequestHeaders(headers);
    }

    @Override
    protected void applyCookies() {
        final List<String> cookieValues = getCookies().values().stream()
                                                      .flatMap(Collection::stream)
                                                      .map(HttpCookie::toString)
                                                      .collect(toImmutableList());
        if (!cookieValues.isEmpty()) {
            headers.add(HttpHeaderNames.COOKIE, cookieValues);
        }
    }

    @Override
    public HttpMethod getMethod() {
        return httpMethod;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return factoryWrapper.delegate();
    }

    public CompletableFuture<HttpResponse> future() {
        return future;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return write(Flux.from(body));
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        // Prefetch 1 message because Armeria's HttpRequestSubscriber consumes messages one by one.
        return write(Flux.from(body).concatMap(Flux::from, 1));
    }

    private Mono<Void> write(Flux<? extends DataBuffer> body) {
        return doCommit(execute(() -> HttpRequest.of(headers, body.map(factoryWrapper::toHttpData))));
    }

    @Override
    public Mono<Void> setComplete() {
        return isCommitted() ? Mono.empty()
                             : doCommit(execute(() -> HttpRequest.of(headers)));
    }

    private Supplier<Mono<Void>> execute(Supplier<HttpRequest> supplier) {
        return () -> Mono.defer(() -> {
            assert request == null : request;
            request = supplier.get();
            future.complete(client.execute(request));
            return Mono.fromFuture(request.completionFuture());
        });
    }

    @VisibleForTesting
    @Nullable
    HttpRequest request() {
        return request;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("client", client)
                          .add("headers", headers)
                          .add("factoryWrapper", factoryWrapper)
                          .add("httpMethod", httpMethod)
                          .add("uri", uri)
                          .add("request", request)
                          .toString();
    }

    private static void setDefaultRequestHeaders(HttpHeaders headers) {
        if (!headers.contains(HttpHeaderNames.ACCEPT)) {
            headers.add(HttpHeaderNames.ACCEPT, "*/*");
        }
    }
}
