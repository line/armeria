/*
 * Copyright 2015 LINE Corporation
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
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * Creates a new client that connects to a specified {@link URI}.
 * If you are creating an {@link WebClient}, it is recommended to use the factory methods in
 * {@link WebClient}.
 */
public final class Clients {

    /**
     * Creates a new client that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is invalid or
     *                                  the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(String uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the {@link URI} of the server endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link URI} is invalid or
     *                                  the specified {@code clientType} is unsupported for the scheme
     */
    public static <T> T newClient(URI uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@code scheme} using
     * the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme} represented as a {@link String}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code scheme} is invalid or
     *                                  the specified {@code clientType} is unsupported for
     *                                  the specified {@code scheme}.
     */
    public static <T> T newClient(String scheme, Endpoint endpoint, Class<T> clientType) {
        return builder(scheme, endpoint).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme} using
     * the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link Scheme}.
     */
    public static <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType) {
        return builder(scheme, endpoint).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link SessionProtocol}
     * using the default {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link SessionProtocol} or
     *                                  {@link SerializationFormat} is required.
     */
    public static <T> T newClient(SessionProtocol protocol, Endpoint endpoint, Class<T> clientType) {
        return builder(protocol, endpoint).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(String)} and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(String uri, Class<T> clientType, ClientOptionValue<?>... options) {
        return builder(uri).options(options).build(clientType);
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
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(String)} and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(String uri, Class<T> clientType, ClientOptions options) {
        return builder(uri).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using the specified
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(String)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, String uri,
                                  Class<T> clientType, ClientOptionValue<?>... options) {

        return builder(uri).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using the specified
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(String)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, String uri,
                                  Class<T> clientType, ClientOptions options) {
        return builder(uri).factory(factory).options(options).build(clientType);
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
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(URI)} and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(URI uri, Class<T> clientType, ClientOptionValue<?>... options) {
        return builder(uri).options(options).build(clientType);
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
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(URI)} and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        return builder(uri).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the specified
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(URI)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, URI uri, Class<T> clientType,
                                  ClientOptionValue<?>... options) {
        return builder(uri).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the specified
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} or
     *                                  the specified {@code clientType} is unsupported for the scheme
     *
     * @deprecated Use {@link #builder(URI)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, URI uri, Class<T> clientType, ClientOptions options) {
        return builder(uri).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link SessionProtocol} and
     * the {@link SerializationFormat} using the default {@link ClientFactory}.
     *
     * @param protocol the session protocol
     * @param format the {@link SerializationFormat} for remote procedure call
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link SessionProtocol} and
     *                                  {@link SerializationFormat}, or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)}
     *             and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(SessionProtocol protocol, SerializationFormat format, Endpoint endpoint,
                                  Class<T> clientType, ClientOptionValue<?>... options) {
        final Scheme scheme = Scheme.of(requireNonNull(format, "format"),
                                        requireNonNull(protocol, "protocol"));
        return builder(scheme, endpoint).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link SessionProtocol} and
     * the {@link SerializationFormat} using the default {@link ClientFactory}.
     *
     * @param protocol the session protocol
     * @param format the {@link SerializationFormat} for remote procedure call
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link SessionProtocol} and
     *                                  {@link SerializationFormat}, or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)} and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(SessionProtocol protocol, SerializationFormat format, Endpoint endpoint,
                                  Class<T> clientType, ClientOptions options) {
        final Scheme scheme = Scheme.of(requireNonNull(format, "format"),
                                        requireNonNull(protocol, "protocol"));
        return builder(scheme, endpoint).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link SessionProtocol} and
     * the {@link SerializationFormat} using the specified {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param protocol the session protocol
     * @param format the {@link SerializationFormat} for remote procedure call
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link SessionProtocol} and
     *                                  {@link SerializationFormat}, or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, SessionProtocol protocol, SerializationFormat format,
                                  Endpoint endpoint, Class<T> clientType, ClientOptionValue<?>... options) {
        final Scheme scheme = Scheme.of(requireNonNull(format, "format"),
                                        requireNonNull(protocol, "protocol"));
        return builder(scheme, endpoint).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link SessionProtocol} and
     * the {@link SerializationFormat} using the specified {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param protocol the session protocol
     * @param format the {@link SerializationFormat} for remote procedure call
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link SessionProtocol} and
     *                                  {@link SerializationFormat}, or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, SessionProtocol protocol, SerializationFormat format,
                                  Endpoint endpoint, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = Scheme.of(requireNonNull(format, "format"),
                                        requireNonNull(protocol, "protocol"));
        return builder(scheme, endpoint).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme} using
     * the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the specified {@link Scheme} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)}
     *             and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType,
                                  ClientOptionValue<?>... options) {
        return builder(scheme, endpoint).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme} using
     * the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the specified {@link Scheme} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)} and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType,
                                  ClientOptions options) {
        return builder(scheme, endpoint).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme} using
     * the specified {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param scheme the {@link Scheme}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the specified {@link Scheme} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, Scheme scheme, Endpoint endpoint, Class<T> clientType,
                                  ClientOptionValue<?>... options) {
        return builder(scheme, endpoint).factory(factory).options(options).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme} using
     * the specified {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param scheme the {@link Scheme}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the specified {@link Scheme} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     *
     * @deprecated Use {@link #builder(Scheme, Endpoint)}, {@link ClientBuilder#factory(ClientFactory)}
     *             and {@link ClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    public static <T> T newClient(ClientFactory factory, Scheme scheme, Endpoint endpoint, Class<T> clientType,
                                  ClientOptions options) {
        return builder(scheme, endpoint).factory(factory).options(options).build(clientType);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified {@code uri}.
     */
    public static ClientBuilder builder(String uri) {
        return new ClientBuilder(URI.create(requireNonNull(uri, "uri")), null, null, null);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified {@link URI}.
     */
    public static ClientBuilder builder(URI uri) {
        return new ClientBuilder(requireNonNull(uri, "uri"), null, null, null);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@code scheme}.
     */
    public static ClientBuilder builder(String scheme, Endpoint endpoint) {
        return new ClientBuilder(null, Scheme.parse(requireNonNull(scheme, "scheme")),
                                 null, requireNonNull(endpoint, "endpoint"));
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link Scheme}.
     */
    public static ClientBuilder builder(Scheme scheme, Endpoint endpoint) {
        return new ClientBuilder(null, requireNonNull(scheme, "scheme"),
                                 null, requireNonNull(endpoint, "endpoint"));
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link SessionProtocol}.
     */
    public static ClientBuilder builder(SessionProtocol protocol, Endpoint endpoint) {
        return new ClientBuilder(null, null,
                                 requireNonNull(protocol, "protocol"),
                                 requireNonNull(endpoint, "endpoint"));
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
     * WebClient derivedWebClient = Clients.newDerivedClient(webClient, options -> {
     *     ClientOptionsBuilder builder = options.toBuilder();
     *     builder.decorator(...);     // Add a decorator.
     *     builder.addHttpHeader(...); // Add an HTTP header.
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
        final ClientBuilder builder = builder(params.uri());
        builder.factory(params.factory());
        builder.options(configurator.apply(params.options()));

        return newDerivedClient(builder, params.clientType());
    }

    @SuppressWarnings("unchecked")
    private static <T> T newDerivedClient(ClientBuilder builder, Class<?> clientType) {
        return builder.build((Class<T>) clientType);
    }

    private static ClientBuilder newDerivedBuilder(ClientBuilderParams params) {
        final ClientBuilder builder = builder(params.uri());
        builder.factory(params.factory());
        builder.options(params.options());
        return builder;
    }

    private static ClientBuilderParams builderParams(Object client) {
        requireNonNull(client, "client");
        final ClientBuilderParams params = ClientFactory.ofDefault().clientBuilderParams(client);
        if (params == null) {
            throw new IllegalArgumentException("derivation not supported by: " + client.getClass().getName());
        }

        return params;
    }

    /**
     * Unwraps the specified client into the object of the specified {@code type}.
     * Use this method instead of an explicit downcast. For example:
     * <pre>{@code
     * WebClient client = WebClient.builder(...)
     *                             .decorator(LoggingClient.newDecorator())
     *                             .build();
     *
     * LoggingClient unwrapped = Clients.unwrap(client, LoggingClient.class);
     *
     * // If the client implements Unwrappable, you can just use the 'as()' method.
     * LoggingClient unwrapped2 = client.as(LoggingClient.class);
     * }</pre>
     *
     * @param type the type of the object to return
     * @return the object of the specified {@code type} if found, or {@code null} if not found.
     *
     * @see Client#as(Class)
     * @see ClientFactory#unwrap(Object, Class)
     * @see Unwrappable
     */
    @Nullable
    public static <T> T unwrap(Object client, Class<T> type) {
        final ClientBuilderParams params = ClientFactory.ofDefault().clientBuilderParams(client);
        if (params == null) {
            return null;
        }

        return params.factory().unwrap(client, type);
    }

    /**
     * Sets the specified HTTP header in a thread-local variable so that the header is sent by the client call
     * made from the current thread. Use the {@code try-with-resources} block with the returned
     * {@link SafeCloseable} to unset the thread-local variable automatically:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     *
     * try (SafeCloseable ignored = withHttpHeader(AUTHORIZATION, myCredential)) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the header manipulation:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
     *
     * try (SafeCloseable ignored = withHttpHeader(USER_AGENT, myAgent)) {
     *     for (String secret : secrets) {
     *         try (SafeCloseable ignored2 = withHttpHeader(AUTHORIZATION, secret)) {
     *             // Both USER_AGENT and AUTHORIZATION will be set.
     *             client.executeSomething(..);
     *         }
     *     }
     * }
     * }</pre>
     *
     * @see #withHttpHeaders(Function)
     */
    public static SafeCloseable withHttpHeader(CharSequence name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        return withHttpHeaders(headers -> headers.toBuilder().set(name, value).build());
    }

    /**
     * Sets the specified HTTP header in a thread-local variable so that the header is sent by the client call
     * made from the current thread. Use the {@code try-with-resources} block with the returned
     * {@link SafeCloseable} to unset the thread-local variable automatically:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
     * import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
     *
     * try (SafeCloseable ignored = withHttpHeader(CONTENT_TYPE, JSON_UTF_8)) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the header manipulation:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
     * import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
     *
     * try (SafeCloseable ignored = withHttpHeader(CONTENT_TYPE, JSON_UTF_8)) {
     *     for (String secret : secrets) {
     *         try (SafeCloseable ignored2 = withHttpHeader(AUTHORIZATION, secret)) {
     *             // Both CONTENT_TYPE and AUTHORIZATION will be set.
     *             client.executeSomething(..);
     *         }
     *     }
     * }
     * }</pre>
     *
     * @see #withHttpHeaders(Function)
     */
    public static SafeCloseable withHttpHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        return withHttpHeaders(headers -> headers.toBuilder().setObject(name, value).build());
    }

    /**
     * Sets the specified HTTP header manipulating function in a thread-local variable so that the manipulated
     * headers are sent by the client call made from the current thread. Use the {@code try-with-resources}
     * block with the returned {@link SafeCloseable} to unset the thread-local variable automatically:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
     *
     * try (SafeCloseable ignored = withHttpHeaders(headers -> {
     *     return headers.toBuilder()
     *                   .set(HttpHeaders.AUTHORIZATION, myCredential)
     *                   .set(HttpHeaders.USER_AGENT, myAgent)
     *                   .build();
     * })) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the header manipulation:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
     *
     * try (SafeCloseable ignored = withHttpHeaders(h -> {
     *          return h.toBuilder()
     *                  .set(USER_AGENT, myAgent)
     *                  .build();
     *      })) {
     *     for (String secret : secrets) {
     *         try (SafeCloseable ignored2 = withHttpHeaders(h -> {
     *                  return h.toBuilder()
     *                          .set(AUTHORIZATION, secret)
     *                          .build();
     *              })) {
     *             // Both USER_AGENT and AUTHORIZATION will be set.
     *             client.executeSomething(..);
     *         }
     *     }
     * }
     * }</pre>
     *
     * @see #withHttpHeader(CharSequence, String)
     */
    public static SafeCloseable withHttpHeaders(
            Function<? super HttpHeaders, ? extends HttpHeaders> headerManipulator) {
        requireNonNull(headerManipulator, "headerManipulator");
        return withContextCustomizer(ctx -> {
            final HttpHeaders manipulatedHeaders = headerManipulator.apply(ctx.additionalRequestHeaders());
            ctx.setAdditionalRequestHeaders(manipulatedHeaders);
        });
    }

    /**
     * Sets the specified {@link ClientRequestContext} customization function in a thread-local variable so that
     * the customized context is used when the client invokes a request from the current thread. Use the
     * {@code try-with-resources} block with the returned {@link SafeCloseable} to unset the thread-local
     * variable automatically:
     * <pre>{@code
     * try (SafeCloseable ignored = withContextCustomizer(ctx -> {
     *     ctx.setAttr(USER_ID, userId);
     *     ctx.setAttr(USER_SECRET, secret);
     * })) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the request context customization:
     * <pre>{@code
     * try (SafeCloseable ignored = withContextCustomizer(ctx -> ctx.setAttr(USER_ID, userId))) {
     *     String secret = client.getSecret();
     *     try (SafeCloseable ignored2 = withContextCustomizer(ctx -> ctx.setAttr(USER_SECRET, secret))) {
     *         // Both USER_ID and USER_SECRET will be set.
     *         client.executeSomething(..);
     *     }
     * }
     * }</pre>
     * Note that certain properties of {@link ClientRequestContext}, such as:
     * <ul>
     *   <li>{@link ClientRequestContext#endpoint()}</li>
     *   <li>{@link ClientRequestContext#localAddress()}</li>
     *   <li>{@link ClientRequestContext#remoteAddress()}</li>
     * </ul>
     * may be {@code null} while the customizer function runs, because the target host of the {@link Request}
     * is not determined yet.
     *
     * @see #withHttpHeaders(Function)
     */
    public static SafeCloseable withContextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        requireNonNull(contextCustomizer, "contextCustomizer");

        final ClientThreadLocalState customizers = ClientThreadLocalState.maybeCreate();
        customizers.add(contextCustomizer);

        return new SafeCloseable() {
            boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }

                closed = true;
                customizers.remove(contextCustomizer);
            }
        };
    }

    /**
     * Prepare to capture the {@link ClientRequestContext} of the next request sent from the current thread.
     * Use the {@code try-with-resources} block with the returned {@link ClientRequestContextCaptor}
     * to retrieve the captured {@link ClientRequestContext} and to unset the thread-local variable
     * automatically.
     * <pre>{@code
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     WebClient.of().get("https://www.example.com/hello");
     *     ClientRequestContext ctx = captor.get();
     *     assert ctx.path().equals("/hello");
     * }}</pre>
     * Note that you can also capture more than one {@link ClientRequestContext}:
     * <pre>{@code
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     WebClient.of().get("https://www.example.com/foo");
     *     WebClient.of().get("https://www.example.com/bar");
     *     List<ClientRequestContext> contexts = captor.getAll();
     *     assert contexts.get(0).path().equals("/foo");
     *     assert contexts.get(1).path().equals("/bar");
     * }}</pre>
     */
    public static ClientRequestContextCaptor newContextCaptor() {
        return ClientThreadLocalState.maybeCreate().newContextCaptor();
    }

    private Clients() {}
}
