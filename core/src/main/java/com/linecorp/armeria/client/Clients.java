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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.internal.client.ClientBuilderParamsUtil;
import com.linecorp.armeria.internal.client.ClientThreadLocalState;
import com.linecorp.armeria.internal.client.ClientUtil;

/**
 * Creates a new client that connects to a specified {@link URI}.
 * If you are creating a {@link WebClient}, it is recommended to use the factory methods in
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
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the specified
     *                                  {@code clientType} is unsupported for the {@code uri}'s scheme
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
     * @throws IllegalArgumentException if the specified {@link URI} is invalid, or the specified
     *                                  {@code clientType} is unsupported for the {@link URI}'s scheme
     */
    public static <T> T newClient(URI uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with the specified
     * {@code scheme} using the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme} represented as a {@link String}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code scheme} is invalid or
     *                                  the specified {@code clientType} is unsupported for
     *                                  the specified {@code scheme}.
     */
    public static <T> T newClient(String scheme, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(scheme, endpointGroup).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with the specified
     * {@code scheme} and {@code path} using the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme} represented as a {@link String}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code scheme} is invalid or
     *                                  the specified {@code clientType} is unsupported for
     *                                  the specified {@code scheme}.
     */
    public static <T> T newClient(String scheme, EndpointGroup endpointGroup, String path,
                                  Class<T> clientType) {
        return builder(scheme, endpointGroup, path).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with the specified
     * {@link Scheme} using the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link Scheme}.
     */
    public static <T> T newClient(Scheme scheme, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(scheme, endpointGroup).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with the specified
     * {@link Scheme} and {@code path} using the default {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link Scheme}.
     */
    public static <T> T newClient(Scheme scheme, EndpointGroup endpointGroup, String path,
                                  Class<T> clientType) {
        return builder(scheme, endpointGroup, path).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} using the default {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link SessionProtocol} or
     *                                  {@link SerializationFormat} is required.
     */
    public static <T> T newClient(SessionProtocol protocol, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(protocol, endpointGroup).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} and {@code path} using the default {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link SessionProtocol} or
     *                                  {@link SerializationFormat} is required.
     */
    public static <T> T newClient(SessionProtocol protocol, EndpointGroup endpointGroup, String path,
                                  Class<T> clientType) {
        return builder(protocol, endpointGroup, path).build(clientType);
    }

    /**
     * Creates a new client that is preprocessed the specified {@link ClientPreprocessors} with
     * using the default {@link ClientFactory}.
     *
     * @param preprocessors the {@link ClientPreprocessors}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link SerializationFormat} or
     *                                  {@link ClientPreprocessors}
     */
    public static <T> T  newClient(ClientPreprocessors preprocessors, Class<T> clientType) {
        return builder(preprocessors).build(clientType);
    }

    /**
     * Creates a new client that is preprocessed the specified {@link ClientPreprocessors} with
     * using the default {@link ClientFactory}.
     *
     * @param serializationFormat the {@link SerializationFormat}
     * @param preprocessors the {@link ClientPreprocessors}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link SerializationFormat} or
     *                                  {@link ClientPreprocessors}
     */
    public static <T> T newClient(SerializationFormat serializationFormat, ClientPreprocessors preprocessors,
                                  Class<T> clientType) {
        return builder(serializationFormat, preprocessors).build(clientType);
    }

    /**
     * Creates a new client that is preprocessed the specified {@link ClientPreprocessors} with
     * using the default {@link ClientFactory}.
     *
     * @param serializationFormat the {@link SerializationFormat}
     * @param preprocessors the {@link ClientPreprocessors}
     * @param clientType the type of the new client
     * @param path the prefix path of the new client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link SerializationFormat} or
     *                                  {@link ClientPreprocessors}
     */
    public static <T> T newClient(SerializationFormat serializationFormat, ClientPreprocessors preprocessors,
                                  Class<T> clientType, String path) {
        return builder(serializationFormat, preprocessors, path).build(clientType);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified {@code uri}.
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the specified
     *                                  {@code clientType} is unsupported for the {@code uri}'s scheme
     */
    public static ClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified {@link URI}.
     *
     * @throws IllegalArgumentException if the specified {@link URI} is invalid, or the specified
     *                                  {@code clientType} is unsupported for the {@link URI}'s scheme
     */
    public static ClientBuilder builder(URI uri) {
        return new ClientBuilder(requireNonNull(uri, "uri"));
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@code scheme}.
     *
     * @throws IllegalArgumentException if the {@code scheme} is invalid.
     */
    public static ClientBuilder builder(String scheme, EndpointGroup endpointGroup) {
        return builder(Scheme.parse(requireNonNull(scheme, "scheme")), endpointGroup);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@code scheme} and {@code path}.
     *
     * @throws IllegalArgumentException if the {@code scheme} is invalid.
     */
    public static ClientBuilder builder(String scheme, EndpointGroup endpointGroup, String path) {
        return builder(Scheme.parse(requireNonNull(scheme, "scheme")), endpointGroup, path);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@link SessionProtocol}.
     */
    public static ClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(Scheme.of(SerializationFormat.NONE, requireNonNull(protocol, "protocol")),
                       endpointGroup);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@link SessionProtocol} and {@code path}.
     */
    public static ClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup,
                                        String path) {
        return builder(Scheme.of(SerializationFormat.NONE, requireNonNull(protocol, "protocol")),
                       endpointGroup, path);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@link Scheme}.
     */
    public static ClientBuilder builder(Scheme scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        return new ClientBuilder(scheme, endpointGroup, null);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@link Scheme} and {@code path}.
     */
    public static ClientBuilder builder(Scheme scheme, EndpointGroup endpointGroup, String path) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(path, "path");
        return new ClientBuilder(scheme, endpointGroup, path);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that is configured with the specified
     * {@link ClientPreprocessors}.
     */
    public static ClientBuilder builder(ClientPreprocessors preprocessors) {
        requireNonNull(preprocessors, "preprocessors");
        return new ClientBuilder(SerializationFormat.NONE, preprocessors, null);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that is configured with the specified
     * {@link ClientPreprocessors}.
     */
    public static ClientBuilder builder(SerializationFormat serializationFormat,
                                        ClientPreprocessors preprocessors) {
        requireNonNull(serializationFormat, "serializationFormat");
        requireNonNull(preprocessors, "preprocessors");
        return new ClientBuilder(serializationFormat, preprocessors, null);
    }

    /**
     * Returns a new {@link ClientBuilder} that builds the client that is configured with the specified
     * {@link SerializationFormat}, {@link ClientPreprocessors} and {@param path}.
     */
    public static ClientBuilder builder(SerializationFormat serializationFormat,
                                        ClientPreprocessors preprocessors, String path) {
        requireNonNull(serializationFormat, "serializationFormat");
        requireNonNull(preprocessors, "preprocessors");
        requireNonNull(path, "path");
        return new ClientBuilder(serializationFormat, preprocessors, path);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * and the specified {@code additionalOptions}.
     *
     * @see ClientBuilder ClientBuilder, for more information about how the base options and
     *                    additional options are merged when a derived client is created.
     */
    public static <T> T newDerivedClient(T client, ClientOptionValue<?>... additionalOptions) {
        requireNonNull(additionalOptions, "additionalOptions");
        return newDerivedClient(client, ImmutableList.copyOf(additionalOptions));
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * and the specified {@code additionalOptions}.
     *
     * @see ClientBuilder ClientBuilder, for more information about how the base options and
     *                    additional options are merged when a derived client is created.
     */
    @SuppressWarnings("unchecked")
    public static <T> T newDerivedClient(T client, Iterable<ClientOptionValue<?>> additionalOptions) {
        final ClientBuilderParams params = builderParams(client);
        final ClientOptions newOptions = ClientOptions.builder()
                                                      .options(params.options())
                                                      .options(additionalOptions)
                                                      .build();
        final ClientBuilderParams newParams = params.paramsBuilder()
                                                    .options(newOptions)
                                                    .build();
        return (T) newOptions.factory().newClient(newParams);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * but with different {@link ClientOption}s. For example:
     *
     * <pre>{@code
     * WebClient derivedWebClient = Clients.newDerivedClient(webClient, options -> {
     *     ClientOptionsBuilder builder = options.toBuilder();
     *     builder.decorator(...); // Add a decorator.
     *     builder.addHeader(...); // Add an HTTP header.
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
        final ClientBuilder builder = newDerivedBuilder(params, false);
        builder.options(configurator.apply(params.options()));

        return newDerivedClient(builder, params.clientType());
    }

    @SuppressWarnings("unchecked")
    private static <T> T newDerivedClient(ClientBuilder builder, Class<?> clientType) {
        return builder.build((Class<T>) clientType);
    }

    private static ClientBuilder newDerivedBuilder(ClientBuilderParams params, boolean setOptions) {
        final ClientBuilder builder = builder(params.scheme(), params.endpointGroup(),
                                              params.absolutePathRef());
        if (setOptions) {
            builder.options(params.options());
        }
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

        return params.options().factory().unwrap(client, type);
    }

    /**
     * Sets the specified HTTP header in a thread-local variable so that the header is sent by the client call
     * made from the current thread. Use the {@code try-with-resources} block with the returned
     * {@link SafeCloseable} to unset the thread-local variable automatically:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     *
     * try (SafeCloseable ignored = withHeader(AUTHORIZATION, myCredential)) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the header manipulation:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
     *
     * try (SafeCloseable ignored = withHeader(USER_AGENT, myAgent)) {
     *     for (String secret : secrets) {
     *         try (SafeCloseable ignored2 = withHeader(AUTHORIZATION, secret)) {
     *             // Both USER_AGENT and AUTHORIZATION will be set.
     *             client.executeSomething(..);
     *         }
     *     }
     * }
     * }</pre>
     *
     * <p>Note that the specified header will be stored into
     * {@link ClientRequestContext#additionalRequestHeaders()} which takes precedence over
     * {@link HttpRequest#headers()}.
     *
     * @see #withHeaders(Consumer)
     */
    @MustBeClosed
    public static SafeCloseable withHeader(CharSequence name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        return withHeaders(headersBuilder -> {
            headersBuilder.set(name, value);
        });
    }

    /**
     * Sets the specified HTTP header in a thread-local variable so that the header is sent by the client call
     * made from the current thread. Use the {@code try-with-resources} block with the returned
     * {@link SafeCloseable} to unset the thread-local variable automatically:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
     * import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
     *
     * try (SafeCloseable ignored = withHeader(CONTENT_TYPE, JSON_UTF_8)) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the header manipulation:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
     * import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
     *
     * try (SafeCloseable ignored = withHeader(CONTENT_TYPE, JSON_UTF_8)) {
     *     for (String secret : secrets) {
     *         try (SafeCloseable ignored2 = withHeader(AUTHORIZATION, secret)) {
     *             // Both CONTENT_TYPE and AUTHORIZATION will be set.
     *             client.executeSomething(..);
     *         }
     *     }
     * }
     * }</pre>
     *
     * <p>Note that the specified header will be stored into
     * {@link ClientRequestContext#additionalRequestHeaders()} which takes precedence over
     * {@link HttpRequest#headers()}.
     *
     * @see #withHeaders(Consumer)
     */
    @MustBeClosed
    public static SafeCloseable withHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        return withHeaders(headersBuilder -> {
            headersBuilder.setObject(name, value);
        });
    }

    /**
     * Sets the specified {@link Consumer}, which mutates HTTP headers, in a thread-local variable so that the
     * mutated headers are sent by the client call made from the current thread.
     * Use the {@code try-with-resources} block with the returned {@link SafeCloseable} to unset the
     * thread-local variable automatically:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
     *
     * try (SafeCloseable ignored = withHeaders(builder -> {
     *     builder.set(HttpHeaders.AUTHORIZATION, myCredential)
     *            .set(HttpHeaders.USER_AGENT, myAgent);
     * })) {
     *     client.executeSomething(..);
     * }
     * }</pre>
     * You can also nest the header mutation:
     * <pre>{@code
     * import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
     * import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
     *
     * try (SafeCloseable ignored = withHeaders(builder -> {
     *          builder.set(USER_AGENT, myAgent);
     *      })) {
     *     for (String secret : secrets) {
     *         try (SafeCloseable ignored2 = withHeaders(builder -> {
     *                  builder.set(AUTHORIZATION, secret);
     *              })) {
     *             // Both USER_AGENT and AUTHORIZATION will be set.
     *             client.executeSomething(..);
     *         }
     *     }
     * }
     * }</pre>
     *
     * <p>Note that the mutated headers will be stored into
     * {@link ClientRequestContext#additionalRequestHeaders()} which takes precedence over
     * {@link HttpRequest#headers()}.
     *
     * @see #withHeader(CharSequence, String)
     */
    @MustBeClosed
    public static SafeCloseable withHeaders(Consumer<HttpHeadersBuilder> headerMutator) {
        requireNonNull(headerMutator, "headerMutator");
        return withContextCustomizer(ctx -> {
            ctx.mutateAdditionalRequestHeaders(headerMutator);
        });
    }

    /**
     * Sets the specified {@link ClientRequestContext} customizer function in a thread-local variable so that
     * the customized context is used when the client invokes a request from the current thread.
     * The given customizer function is evaluated after the customizer function specified with
     * {@link ClientBuilder#contextCustomizer(Consumer)}.
     *
     * <p>Use the {@code try-with-resources} block with the returned {@link SafeCloseable} to unset the
     * thread-local variable automatically:
     * <pre>{@code
     * // Good:
     * try (SafeCloseable ignored = withContextCustomizer(ctx -> {
     *     ctx.setAttr(USER_ID, userId);
     *     ctx.setAttr(USER_SECRET, secret);
     * })) {
     *     client.executeSomething(..);
     * }
     *
     * // Bad:
     * try (SafeCloseable ignored = withContextCustomizer(ctx -> {
     *     ctx.setAttr(USER_ID, userId);
     *     ctx.setAttr(USER_SECRET, secret);
     * })) {
     *     executor.execute(() -> {
     *         // The variables in USER_ID and USER_SECRET will not be propagated to the context.
     *         // client.executeSomething() must be invoked by the same thread
     *         // that called withContextCustomizer().
     *         client.executeSomething(..);
     *     });
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
     *
     * <p>Note that certain properties of {@link ClientRequestContext}, such as:
     * <ul>
     *   <li>{@link ClientRequestContext#endpoint()}</li>
     *   <li>{@link ClientRequestContext#localAddress()}</li>
     *   <li>{@link ClientRequestContext#remoteAddress()}</li>
     * </ul>
     * may be {@code null} while the customizer function runs, because the target host of the {@link Request}
     * is not determined yet.
     *
     * @see #withHeaders(Consumer)
     * @see ClientBuilder#contextCustomizer(Consumer)
     */
    @MustBeClosed
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

    /**
     * Returns {@code true} if the specified {@code uri} is an undefined {@link URI}, which signifies that
     * a {@link Client}, was created without a {@link URI} or {@link EndpointGroup}. For example,
     * {@code isUndefinedUri(WebClient.of().uri())} will return {@code true}.
     */
    public static boolean isUndefinedUri(URI uri) {
        return (uri == ClientUtil.UNDEFINED_URI ||
                ClientBuilderParamsUtil.UNDEFINED_URI_AUTHORITY.equals(uri.getAuthority())) &&
                uri.getPort() == 1;
    }

    private Clients() {}
}
