/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.spring.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Map;

import org.reactivestreams.Publisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.service.invoker.AbstractReactorHttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.ReactiveHttpRequestValues;
import org.springframework.web.service.invoker.ReactorHttpExchangeAdapter;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilderFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.spring.internal.client.ArmeriaClientHttpRequest;
import com.linecorp.armeria.spring.internal.client.ArmeriaClientHttpResponse;
import com.linecorp.armeria.spring.internal.common.DataBufferFactoryWrapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@link ReactorHttpExchangeAdapter} implementation for the Armeria {@link WebClient}.
 * This class is used to create <a href="https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface">
 * Spring HTTP Interface</a> on top of Armeria {@link WebClient}.
 *
 * <p>Example usage:
 * <pre>{@code
 * import com.linecorp.armeria.client.WebClient;
 * import org.springframework.web.service.invoker.HttpServiceProxyFactory;
 *
 * WebClient webClient = ...;
 * ArmeriaHttpExchangeAdapter adapter =
 *   ArmeriaHttpExchangeAdapter.of(webClient);
 * MyService service =
 *   HttpServiceProxyFactory.builderFor(adapter)
 *                          .build()
 *                          .createClient(MyService.class);
 * }</pre>
 */
@UnstableApi
public final class ArmeriaHttpExchangeAdapter extends AbstractReactorHttpExchangeAdapter {

    /**
     * Creates a new instance with the specified {@link WebClient}.
     */
    public static ArmeriaHttpExchangeAdapter of(WebClient webClient) {
        return builder(webClient).build();
    }

    /**
     * Returns a new {@link ArmeriaHttpExchangeAdapterBuilder} with the specified {@link WebClient}.
     */
    public static ArmeriaHttpExchangeAdapterBuilder builder(WebClient webClient) {
        return new ArmeriaHttpExchangeAdapterBuilder(webClient);
    }

    private final WebClient webClient;
    private final ExchangeStrategies exchangeStrategies;
    private final UriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory();
    @Nullable
    private final StatusHandler statusHandler;

    ArmeriaHttpExchangeAdapter(WebClient webClient, ExchangeStrategies exchangeStrategies,
                               @Nullable StatusHandler statusHandler) {
        this.webClient = webClient;
        this.exchangeStrategies = exchangeStrategies;
        this.statusHandler = statusHandler;
    }

    @Override
    public Mono<Void> exchangeForMono(HttpRequestValues requestValues) {
        return execute(requestValues).flatMap(ClientResponse::releaseBody);
    }

    @Override
    public Mono<HttpHeaders> exchangeForHeadersMono(HttpRequestValues requestValues) {
        return execute(requestValues).map(response -> response.headers().asHttpHeaders());
    }

    @Override
    public <T> Mono<T> exchangeForBodyMono(HttpRequestValues requestValues,
                                           ParameterizedTypeReference<T> bodyType) {
        return execute(requestValues).flatMap(response -> response.bodyToMono(bodyType));
    }

    @Override
    public <T> Flux<T> exchangeForBodyFlux(HttpRequestValues requestValues,
                                           ParameterizedTypeReference<T> bodyType) {
        return execute(requestValues).flatMapMany(response -> response.bodyToFlux(bodyType));
    }

    @Override
    public Mono<ResponseEntity<Void>> exchangeForBodilessEntityMono(
            HttpRequestValues requestValues) {
        return execute(requestValues).flatMap(ClientResponse::toBodilessEntity);
    }

    @Override
    public <T> Mono<ResponseEntity<T>> exchangeForEntityMono(
            HttpRequestValues requestValues, ParameterizedTypeReference<T> bodyType) {
        return execute(requestValues).flatMap(response -> response.toEntity(bodyType));
    }

    @Override
    public <T> Mono<ResponseEntity<Flux<T>>> exchangeForEntityFlux(
            HttpRequestValues requestValues, ParameterizedTypeReference<T> bodyType) {
        return execute(requestValues).map(response -> {
            final Flux<T> body = response.bodyToFlux(bodyType);
            return ResponseEntity.status(response.statusCode())
                                 .headers(response.headers().asHttpHeaders())
                                 .body(body);
        });
    }

    /**
     * Returns {@code true} because Armeria supports {@link RequestAttribute}. The attributes can be accessed
     * in the Armeria decorators using {@link RequestAttributeAccess#get(ClientRequestContext, String)}.
     */
    @Override
    public boolean supportsRequestAttributes() {
        return true;
    }

    /**
     * Sends the specified {@link HttpRequestValues} to the {@link WebClient} and returns the response.
     *
     * <p><h4>Implementation note</h4>
     * In order to encode {@link HttpRequestValues#getBodyValue()} to {@link HttpData}, we need to convert
     * the {@link HttpRequestValues} to {@link ClientRequest} first. The serialization process is delegated to
     * the {@link ClientRequest}. After that, the request is written to {@link ArmeriaClientHttpRequest} to send
     * the request via the {@link WebClient}.
     *
     * <p>The response handling has to be done in the reverse order. Armeria {@link HttpResponse} is converted
     * into {@link ArmeriaClientHttpResponse} first and then converted into {@link ClientResponse}.
     */
    private Mono<ClientResponse> execute(HttpRequestValues requestValues) {
        final URI uri;
        if (requestValues.getUri() != null) {
            uri = requestValues.getUri();
        } else {
            final String uriTemplate = requestValues.getUriTemplate();
            if (uriTemplate != null) {
                final UriBuilderFactory uriBuilderFactory = requestValues.getUriBuilderFactory();
                final Map<String, String> uriVariables = requestValues.getUriVariables();
                if (uriBuilderFactory != null) {
                    uri = uriBuilderFactory.expand(uriTemplate, uriVariables);
                } else {
                    uri = this.uriBuilderFactory.expand(uriTemplate, uriVariables);
                }
            } else {
                throw new IllegalStateException("Neither full URL nor URI template");
            }
        }

        final Map<String, Object> attributes = requestValues.getAttributes();
        final RequestOptions requestOptions;
        if (attributes.isEmpty()) {
            requestOptions = null;
        } else {
            final RequestOptionsBuilder requestOptionsBuilder = RequestOptions.builder();
            // Don't need to copy the attributes because it is immutable.
            RequestAttributeAccess.set(requestOptionsBuilder, attributes);
            requestOptions = requestOptionsBuilder.build();
        }
        final String path = uri.getRawPath();
        final String query = uri.getRawQuery();
        checkArgument(path != null, "path is undefined: %s", uri);
        final String pathAndQuery = Strings.isNullOrEmpty(query) ? path : path + '?' + query;
        final HttpMethod httpMethod = requestValues.getHttpMethod();
        checkArgument(httpMethod != null, "HTTP method is undefined. requestValues: %s", requestValues);
        final ArmeriaClientHttpRequest request =
                new ArmeriaClientHttpRequest(webClient, httpMethod, pathAndQuery, uri,
                                             DataBufferFactoryWrapper.DEFAULT, requestOptions);

        final Mono<HttpResponse> response = Mono.fromFuture(request.future());
        return toClientRequest(requestValues, httpMethod, uri)
                .writeTo(request, exchangeStrategies)
                .then(response)
                .flatMap(res -> {
                    final ClientRequestContext context = request.context();
                    assert context != null;
                    return toClientResponse(res, context);
                });
    }

    private static <T> ClientRequest toClientRequest(HttpRequestValues requestValues, HttpMethod httpMethod,
                                                     URI uri) {
        final ClientRequest.Builder builder =
                ClientRequest.create(httpMethod, uri)
                             .headers(headers -> headers.addAll(requestValues.getHeaders()))
                             .cookies(cookies -> cookies.addAll(requestValues.getCookies()));

        final Object bodyValue = requestValues.getBodyValue();
        if (bodyValue != null) {
            builder.body(BodyInserters.fromValue(bodyValue));
        } else if (requestValues instanceof ReactiveHttpRequestValues) {
            final ReactiveHttpRequestValues reactiveRequestValues = (ReactiveHttpRequestValues) requestValues;
            @SuppressWarnings("unchecked")
            final Publisher<T> body = (Publisher<T>) reactiveRequestValues.getBodyPublisher();
            if (body != null) {
                @SuppressWarnings("unchecked")
                final ParameterizedTypeReference<T> elementType =
                        (ParameterizedTypeReference<T>) reactiveRequestValues.getBodyPublisherElementType();
                requireNonNull(elementType, "Publisher body element type is required");
                builder.body(body, elementType);
            }
        }

        return builder.build();
    }

    private Mono<ClientResponse> toClientResponse(HttpResponse response, ClientRequestContext ctx) {
        final SplitHttpResponse splitResponse = response.split();
        return Mono.fromFuture(splitResponse.headers()).flatMap(headers -> {
            if (statusHandler != null) {
                final Throwable cause = statusHandler.handle(ctx, headers.status());
                if (cause != null) {
                    // It would be better to release the resources by consuming than sending an RST stream by
                    // aborting. Because:
                    // - The server may have sent a response body already.
                    // - In general, a stream response is not expected with an error status.
                    // - If too many RSTs are sent, it can be mistaken for 'Rapid Reset' attacks.
                    splitResponse.body().subscribe();
                    return Mono.error(cause);
                }
            }

            final ArmeriaClientHttpResponse httpResponse =
                    new ArmeriaClientHttpResponse(headers, splitResponse,
                                                  DataBufferFactoryWrapper.DEFAULT);

            final HttpStatusCode statusCode = HttpStatusCode.valueOf(httpResponse.getRawStatusCode());
            final ClientResponse clientResponse =
                    ClientResponse.create(statusCode, exchangeStrategies)
                                  .cookies(cookies -> cookies.addAll(httpResponse.getCookies()))
                                  .headers(headers0 -> headers0.addAll(httpResponse.getHeaders()))
                                  .body(httpResponse.getBody())
                                  .build();
            return Mono.just(clientResponse);
        });
    }
}
