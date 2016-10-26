/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client;

import java.net.URI;
import java.util.function.Function;

/**
 * Creates a new client that connects to a specified {@link URI}.
 */
public final class Clients {

    /**
     * Creates a new client that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(String uri, Class<T> clientType, ClientOptionValue<?>... options) {
        return newClient(ClientFactory.DEFAULT, uri, clientType, options);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(String uri, Class<T> clientType, ClientOptions options) {
        return newClient(ClientFactory.DEFAULT, uri, clientType, options);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(ClientFactory factory, String uri,
                                  Class<T> clientType, ClientOptionValue<?>... options) {

        return new ClientBuilder(uri).factory(factory).options(options)
                                     .build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(ClientFactory factory, String uri,
                                  Class<T> clientType, ClientOptions options) {
        return new ClientBuilder(uri).factory(factory).options(options)
                                     .build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(URI uri, Class<T> clientType, ClientOptionValue<?>... options) {
        return newClient(ClientFactory.DEFAULT, uri, clientType, options);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        return newClient(ClientFactory.DEFAULT, uri, clientType, options);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(ClientFactory factory, URI uri, Class<T> clientType,
                                  ClientOptionValue<?>... options) {
        return new ClientBuilder(uri).factory(factory).options(options)
                                     .build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                     the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(ClientFactory factory, URI uri, Class<T> clientType,
                                  ClientOptions options) {
        return new ClientBuilder(uri).factory(factory).options(options)
                                     .build(clientType);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * and the specified {@code additionalOptions}.
     *
     * @see ClientOptionsBuilder ClientOptionsBuilder, for more information about how the base options and
     *                           additional options are merged when a derived client is created.
     */
    public static <T> T newDerivedClient(T client, ClientOptionValue<?>... additionalOptions) {
        return asDerivable(client).withOptions(additionalOptions);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * and the specified {@code additionalOptions}.
     *
     * @see ClientOptionsBuilder ClientOptionsBuilder, for more information about how the base options and
     *                           additional options are merged when a derived client is created.
     */
    public static <T> T newDerivedClient(T client, Iterable<ClientOptionValue<?>> additionalOptions) {
        return asDerivable(client).withOptions(additionalOptions);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * but with different {@link ClientOption}s. For example:
     *
     * <pre>{@code
     * HttpClient derivedHttpClient = Clients.newDerivedClient(httpClient, options -> {
     *     ClientOptionsBuilder builder = new ClientOptionsBuilder(options);
     *     builder.decorator(...);   // Add a decorator.
     *     builder.httpHeader(...); // Add an HTTP header.
     *     return builder.build();
     * });
     * }</pre>
     *
     * @param configurator a {@link Function} whose input is the original {@link ClientOptions} of the client
     *                     being derived from and whose output is the {@link ClientOptions} of the new derived
     *                     client
     *
     * @see ClientOptionsBuilder ClientOptionsBuilder, for more information about how the base options and
     *                           additional options are merged when a derived client is created.
     */
    public static <T> T newDerivedClient(
            T client, Function<? super ClientOptions, ClientOptions> configurator) {
        return asDerivable(client).derive(configurator);
    }

    private static <T> ClientOptionDerivable<T> asDerivable(T client) {
        if (!(client instanceof ClientOptionDerivable)) {
            throw new IllegalArgumentException("client does not support derivation: " + client);
        }

        @SuppressWarnings("unchecked")
        final ClientOptionDerivable<T> derivable = (ClientOptionDerivable<T>) client;
        return derivable;
    }

    private Clients() {}
}
