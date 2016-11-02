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

import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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

        return new ClientBuilder(uri).factory(factory).options(options).build(clientType);
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
        return new ClientBuilder(uri).factory(factory).options(options).build(clientType);
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
        return new ClientBuilder(uri).factory(factory).options(options).build(clientType);
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
    public static <T> T newClient(ClientFactory factory, URI uri, Class<T> clientType, ClientOptions options) {
        return new ClientBuilder(uri).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * and the specified {@code additionalOptions}.
     *
     * @see ClientBuilder ClientBuilder, for more information about how the base options and
     *                    additional options are merged when a derived client is created.
     */
    public static <T> T newDerivedClient(T client, ClientOptionValue<?>... additionalOptions) {
        final ClientBuilderParams params = builderParams(client);
        final ClientBuilder builder = newDerivedBuilder(params);
        builder.options(additionalOptions);

        return newDerivedClient(builder, params.clientType());
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * and the specified {@code additionalOptions}.
     *
     * @see ClientBuilder ClientBuilder, for more information about how the base options and
     *                    additional options are merged when a derived client is created.
     */
    public static <T> T newDerivedClient(T client, Iterable<ClientOptionValue<?>> additionalOptions) {
        final ClientBuilderParams params = builderParams(client);
        final ClientBuilder builder = newDerivedBuilder(params);
        builder.options(additionalOptions);

        return newDerivedClient(builder, params.clientType());
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
     * @see ClientBuilder ClientBuilder, for more information about how the base options and
     *                    additional options are merged when a derived client is created.
     * @see ClientOptionsBuilder
     */
    public static <T> T newDerivedClient(
            T client, Function<? super ClientOptions, ClientOptions> configurator) {
        final ClientBuilderParams params = builderParams(client);
        final ClientBuilder builder = new ClientBuilder(params.uri());
        builder.factory(params.factory());
        builder.options(configurator.apply(params.options()));

        return newDerivedClient(builder, params.clientType());
    }

    @SuppressWarnings("unchecked")
    private static <T> T newDerivedClient(ClientBuilder builder, Class<?> clientType) {
        return builder.build((Class<T>) clientType);
    }

    private static ClientBuilder newDerivedBuilder(ClientBuilderParams params) {
        final ClientBuilder builder = new ClientBuilder(params.uri());
        builder.factory(params.factory());
        builder.options(params.options());
        return builder;
    }

    private static ClientBuilderParams builderParams(Object client) {
        requireNonNull(client, "client");
        if (client instanceof ClientBuilderParams) {
            return (ClientBuilderParams) client;
        }

        if (Proxy.isProxyClass(client.getClass())) {
            final InvocationHandler handler = Proxy.getInvocationHandler(client);
            if (handler instanceof ClientBuilderParams) {
                return (ClientBuilderParams) handler;
            }
        }

        throw new IllegalArgumentException("derivation not supported by: " + client.getClass().getName());
    }

    private Clients() {}
}
