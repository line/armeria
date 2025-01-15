/*
 * Copyright 2022 LINE Corporation
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

import java.net.URI;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * A client designed for calling <a href="https://restfulapi.net/">RESTful APIs</a> conveniently.
 */
@UnstableApi
public interface RestClient extends ClientBuilderParams, Unwrappable {

    /**
     * Returns a {@link RestClient} without a base URI using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     */
    static RestClient of() {
        return DefaultRestClient.DEFAULT;
    }

    /**
     * Returns a new {@link RestClient} with the specified {@link WebClient}.
     */
    static RestClient of(WebClient webClient) {
        requireNonNull(webClient, "webClient");

        if (webClient == WebClient.of()) {
            return of();
        }
        return new DefaultRestClient(webClient);
    }

    /**
     * Returns a new {@link RestClient} that connects to the specified {@code uri} using the default options.
     *
     * @param uri the URI of the server endpoint
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClient of(String uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link RestClient} that connects to the specified {@link URI} using the default options.
     *
     * @param uri the {@link URI} of the server endpoint
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClient of(URI uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link RestClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code protocol} using the default {@link ClientFactory} and the default
     * {@link ClientOptions}.
     *
     * @param protocol the session protocol of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClient of(String protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link RestClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} using the default {@link ClientFactory} and the default
     * {@link ClientOptions}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClient of(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link RestClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code protocol} and {@code path} using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     *
     * @param protocol the session protocol of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClient of(String protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link RestClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} and {@code path} using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClient of(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link RestClient} that is preprocessed with the specified {@link HttpPreprocessor}
     * using the default {@link ClientFactory} and the default {@link ClientOptions}.
     *
     * @param httpPreprocessor the preprocessor
     */
    static RestClient of(HttpPreprocessor httpPreprocessor) {
        return builder(httpPreprocessor).build();
    }

    /**
     * Returns a new {@link RestClient} that is preprocessed with the specified {@link HttpPreprocessor}
     * and {@param path} using the default {@link ClientFactory} and the default {@link ClientOptions}.
     *
     * @param httpPreprocessor the preprocessor
     * @param path the path to the endpoint
     */
    static RestClient of(HttpPreprocessor httpPreprocessor, String path) {
        return builder(httpPreprocessor, path).build();
    }

    /**
     * Returns a new {@link RestClientBuilder} created without a base {@link URI}.
     */
    static RestClientBuilder builder() {
        return new RestClientBuilder();
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified base {@code uri}.
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified base {@link URI}.
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClientBuilder builder(URI uri) {
        return new RestClientBuilder(uri);
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified {@code protocol}
     * and base {@link EndpointGroup}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClientBuilder builder(String protocol, EndpointGroup endpointGroup) {
        return builder(SessionProtocol.of(requireNonNull(protocol, "protocol")), endpointGroup);
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified {@link SessionProtocol}
     * and base {@link EndpointGroup}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        return new RestClientBuilder(protocol, endpointGroup, null);
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified {@code protocol}.
     * base {@link EndpointGroup} and path.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClientBuilder builder(String protocol, EndpointGroup endpointGroup, String path) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(path, "path");
        return builder(SessionProtocol.of(protocol), endpointGroup, path);
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified {@link SessionProtocol},
     * base {@link EndpointGroup} and path.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static RestClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(path, "path");
        return new RestClientBuilder(protocol, endpointGroup, path);
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified {@link HttpPreprocessor}.
     */
    static RestClientBuilder builder(HttpPreprocessor httpPreprocessor) {
        requireNonNull(httpPreprocessor, "httpPreprocessor");
        return new RestClientBuilder(httpPreprocessor, null);
    }

    /**
     * Returns a new {@link RestClientBuilder} created with the specified {@link HttpPreprocessor}
     * and path.
     */
    static RestClientBuilder builder(HttpPreprocessor httpPreprocessor, String path) {
        requireNonNull(httpPreprocessor, "httpPreprocessor");
        requireNonNull(path, "path");
        return new RestClientBuilder(httpPreprocessor, path);
    }

    /**
     * Sets an {@link HttpMethod#GET} and the {@code path} and returns a fluent request builder.
     * <pre>{@code
     * RestClient restClient = RestClient.of("...");
     * CompletableFuture<ResponseEntity<Customer>> response =
     *     restClient.get("/api/v1/customers/{customerId}")
     *               .pathParam("customerId", "0000001")
     *               .execute(Customer.class);
     * }</pre>
     */
    default RestClientPreparation get(String path) {
        return path(HttpMethod.GET, path);
    }

    /**
     * Sets an {@link HttpMethod#POST} and the {@code path} and returns a fluent request builder.
     * <pre>{@code
     * RestClient restClient = RestClient.of("...");
     * CompletableFuture<ResponseEntity<Result>> response =
     *     restClient.post("/api/v1/customers")
     *               .contentJson(new Customer(...))
     *               .execute(Result.class);
     * }</pre>
     */
    default RestClientPreparation post(String path) {
        return path(HttpMethod.POST, path);
    }

    /**
     * Sets an {@link HttpMethod#PUT} and the {@code path} and returns a fluent request builder.
     * <pre>{@code
     * RestClient restClient = RestClient.of("...");
     * CompletableFuture<ResponseEntity<Result>> response =
     *     restClient.put("/api/v1/customers")
     *               .contentJson(new Customer(...))
     *               .execute(Result.class);
     * }</pre>
     */
    default RestClientPreparation put(String path) {
        return path(HttpMethod.PUT, path);
    }

    /**
     * Sets an {@link HttpMethod#PATCH} and the {@code path} and returns a fluent request builder.
     * <pre>{@code
     * RestClient restClient = RestClient.of("...");
     * CompletableFuture<ResponseEntity<Result>> response =
     *     restClient.patch("/api/v1/customers")
     *               .contentJson(new Customer(...))
     *               .execute(Result.class);
     * }</pre>
     */
    default RestClientPreparation patch(String path) {
        return path(HttpMethod.PATCH, path);
    }

    /**
     * Sets an {@link HttpMethod#DELETE} and the {@code path} and returns a fluent request builder.
     * <pre>{@code
     * RestClient restClient = RestClient.of("...");
     * CompletableFuture<ResponseEntity<Result>> response =
     *     restClient.delete("/api/v1/customers")
     *               .pathParam("customerId", "0000001")
     *               .execute(Result.class);
     * }</pre>
     */
    default RestClientPreparation delete(String path) {
        return path(HttpMethod.DELETE, path);
    }

    /**
     * Sets the {@link HttpMethod} and the {@code path} and returns a fluent request builder.
     * <pre>{@code
     * RestClient restClient = RestClient.of("...");
     * CompletableFuture<ResponseEntity<Customer>> response =
     *     restClient.path(HttpMethod.GET, "/api/v1/customers/{customerId}")
     *               .pathParam("customerId", "0000001")
     *               .execute(Customer.class);
     * }</pre>
     */
    RestClientPreparation path(HttpMethod method, String path);

    @Override
    HttpClient unwrap();
}
