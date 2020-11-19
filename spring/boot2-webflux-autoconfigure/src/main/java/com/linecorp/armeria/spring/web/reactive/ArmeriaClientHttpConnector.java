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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpResponse;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpResponse;

import reactor.core.publisher.Mono;

/**
 * A {@link ClientHttpConnector} implementation for the Armeria HTTP client.
 *
 * @see ArmeriaClientAutoConfiguration#clientHttpConnector(List, DataBufferFactoryWrapper)
 */
final class ArmeriaClientHttpConnector implements ClientHttpConnector {

    private final List<ArmeriaClientConfigurator> configurators;
    private final DataBufferFactoryWrapper<?> factoryWrapper;

    /**
     * Creates an {@link ArmeriaClientHttpConnector} with the specified
     * {@link ArmeriaClientConfigurator} and the default {@link DataBufferFactoryWrapper}.
     *
     * @param configurator the configurator to be used to build an {@link WebClient}
     */
    @VisibleForTesting
    ArmeriaClientHttpConnector(ArmeriaClientConfigurator configurator) {
        this(ImmutableList.of(requireNonNull(configurator, "configurator")),
             DataBufferFactoryWrapper.DEFAULT);
    }

    /**
     * Creates an {@link ArmeriaClientHttpConnector}.
     *
     * @param configurators the {@link ArmeriaClientConfigurator}s to be used to build an
     *                      {@link WebClient}
     * @param factoryWrapper the factory wrapper to be used to create a {@link DataBuffer}
     */
    ArmeriaClientHttpConnector(Iterable<ArmeriaClientConfigurator> configurators,
                               DataBufferFactoryWrapper<?> factoryWrapper) {
        this.configurators = ImmutableList.copyOf(requireNonNull(configurators, "configurators"));
        this.factoryWrapper = requireNonNull(factoryWrapper, "factoryWrapper");
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
                                  .then(Mono.fromFuture(request.future().thenCompose(this::createResponse)));
        } catch (NullPointerException | IllegalArgumentException e) {
            return Mono.error(e);
        }
    }

    private ArmeriaClientHttpRequest createRequest(HttpMethod method, URI uri) {
        final String scheme = uri.getScheme();
        final String authority = uri.getRawAuthority();
        final String path = uri.getRawPath();
        final String query = uri.getRawQuery();

        checkArgument(!Strings.isNullOrEmpty(authority), "URI is not absolute: %s", uri);
        checkArgument(!Strings.isNullOrEmpty(path), "path is undefined: %s", uri);

        final URI baseUri = URI.create(Strings.isNullOrEmpty(scheme) ? authority : scheme + "://" + authority);
        final WebClientBuilder builder = WebClient.builder(baseUri);
        configurators.forEach(c -> c.configure(builder));

        final String pathAndQuery = Strings.isNullOrEmpty(query) ? path : path + '?' + query;

        return new ArmeriaClientHttpRequest(builder.build(), method, pathAndQuery, uri, factoryWrapper);
    }

    private CompletableFuture<ArmeriaClientHttpResponse> createResponse(HttpResponse response) {
        final ArmeriaHttpResponseBodyStream bodyStream =
                new ArmeriaHttpResponseBodyStream(response, response.defaultSubscriberExecutor());
        return bodyStream.headers().thenApply(
                headers -> new ArmeriaClientHttpResponse(headers, bodyStream, factoryWrapper));
    }
}
