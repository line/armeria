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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.function.Function;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpResponse;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.HttpHeaders;

import reactor.core.publisher.Mono;

/**
 * A {@link ClientHttpConnector} implementation for the Armeria HTTP client.
 */
public final class ArmeriaClientHttpConnector implements ClientHttpConnector {

    /**
     * A default {@link ArmeriaClientConfigurator} which does nothing.
     */
    private static final ArmeriaClientConfigurator IDENTITY = b -> { /* noop */ };

    private final ArmeriaClientConfigurator customizer;
    private final ArmeriaBufferFactory bufferFactory;

    /**
     * Creates an {@link ArmeriaClientHttpConnector} with the default
     * {@link ArmeriaClientConfigurator} and {@link DataBufferFactory}.
     */
    public ArmeriaClientHttpConnector() {
        this(IDENTITY, ArmeriaBufferFactory.DEFAULT);
    }

    /**
     * Creates an {@link ArmeriaClientHttpConnector} with the specified
     * {@link ArmeriaClientConfigurator} and the default {@link DataBufferFactory}.
     *
     * @param customizer the customizer to be used to build an {@link HttpClient}
     */
    public ArmeriaClientHttpConnector(ArmeriaClientConfigurator customizer) {
        this(customizer, ArmeriaBufferFactory.DEFAULT);
    }

    /**
     * Creates an {@link ArmeriaClientHttpConnector} with the specified {@link DataBufferFactory}
     * and the default {@link ArmeriaClientConfigurator}.
     *
     * @param bufferFactory the factory to be used to create a {@link DataBuffer}
     */
    public ArmeriaClientHttpConnector(ArmeriaBufferFactory bufferFactory) {
        this(IDENTITY, bufferFactory);
    }

    /**
     * Creates an {@link ArmeriaClientHttpConnector}.
     *
     * @param customizer the {@link ArmeriaClientConfigurator} to be used to build an
     *                   {@link HttpClient}
     * @param bufferFactory the factory to be used to create a {@link DataBuffer}
     */
    public ArmeriaClientHttpConnector(ArmeriaClientConfigurator customizer,
                                      ArmeriaBufferFactory bufferFactory) {
        this.customizer = requireNonNull(customizer, "customizer");
        this.bufferFactory = requireNonNull(bufferFactory, "bufferFactory");
    }

    @Override
    public Mono<ClientHttpResponse> connect(
            HttpMethod method, URI uri, Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
        try {
            requireNonNull(method, "method");
            requireNonNull(uri, "uri");
            requireNonNull(requestCallback, "requestCallback");

            final ArmeriaClientHttpRequest request = createRequest(method, uri);
            return requestCallback.apply(request)
                                  .then(Mono.fromFuture(request.future()))
                                  .map(ArmeriaHttpClientResponseSubscriber::new)
                                  .flatMap(s -> Mono.fromFuture(s.httpHeadersFuture())
                                                    .map(headers -> createResponse(headers, s)));
        } catch (NullPointerException | IllegalArgumentException e) {
            return Mono.error(e);
        }
    }

    private ArmeriaClientHttpRequest createRequest(HttpMethod method, URI uri) {
        final String scheme = uri.getScheme();
        final String authority = uri.getRawAuthority();
        final String path = uri.getRawPath();
        final String query = uri.getRawQuery();

        checkArgument(!Strings.isNullOrEmpty(authority), "URI is not absolute: " + uri);
        checkArgument(!Strings.isNullOrEmpty(path), "path is undefined: " + uri);

        final URI baseUri = URI.create(Strings.isNullOrEmpty(scheme) ? authority : scheme + "://" + authority);
        final HttpClientBuilder builder = new HttpClientBuilder(baseUri);
        customizer.customize(builder);

        final String pathAndQuery = Strings.isNullOrEmpty(query) ? path : path + '?' + query;

        return new ArmeriaClientHttpRequest(builder.build(), method, pathAndQuery, uri, bufferFactory);
    }

    private ArmeriaClientHttpResponse createResponse(HttpHeaders headers,
                                                     ArmeriaHttpClientResponseSubscriber s) {
        return new ArmeriaClientHttpResponse(headers, s.toResponseBodyPublisher(), bufferFactory);
    }
}
