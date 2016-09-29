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

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override.
 */
public final class ClientBuilder {

    private final URI uri;
    private final Map<ClientOption<?>, ClientOptionValue<?>> options = new LinkedHashMap<>();

    private ClientFactory factory = ClientFactory.DEFAULT;
    private ClientDecorationBuilder decoration;

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@code uri}.
     */
    public ClientBuilder(String uri) {
        this(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@link URI}.
     */
    public ClientBuilder(URI uri) {
        this.uri = requireNonNull(uri, "uri");
    }

    /**
     * Sets the {@link ClientFactory} of the client. The default is {@link ClientFactory#DEFAULT}.
     */
    public ClientBuilder factory(ClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Adds the specified {@link ClientOptions}.
     */
    public ClientBuilder options(ClientOptions options) {
        requireNonNull(options, "options");

        final Map<ClientOption<Object>, ClientOptionValue<Object>> optionMap = options.asMap();
        for (ClientOptionValue<?> o : optionMap.values()) {
            validateOption(o.option());
        }

        this.options.putAll(optionMap);
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public ClientBuilder options(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        for (int i = 0; i < options.length; i++) {
            final ClientOptionValue<?> o = options[i];
            if (o == null) {
                throw new NullPointerException("options[" + i + ']');
            }

            if (o.option() == ClientOption.DECORATION && decoration != null) {
                throw new IllegalArgumentException(
                        "options[" + i + "]: option(" + ClientOption.DECORATION +
                        ") and decorator() are mutually exclusive.");
            }

            this.options.put(o.option(), o);
        }
        return this;
    }

    /**
     * Adds the specified {@link ClientOption} and its {@code value}.
     */
    public <T> ClientBuilder option(ClientOption<T> option, T value) {
        validateOption(option);
        options.put(option, option.newValue(value));
        return this;
    }

    private void validateOption(ClientOption<?> option) {
        requireNonNull(option, "option");
        if (option == ClientOption.DECORATION && decoration != null) {
            throw new IllegalArgumentException(
                    "option(" + ClientOption.DECORATION + ") and decorator() are mutually exclusive.");
        }
    }

    /**
     * Sets the default timeout of a socket write attempt in milliseconds.
     *
     * @param defaultWriteTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultWriteTimeoutMillis(long defaultWriteTimeoutMillis) {
        return option(ClientOption.DEFAULT_WRITE_TIMEOUT_MILLIS, defaultWriteTimeoutMillis);
    }

    /**
     * Sets the default timeout of a socket write attempt.
     *
     * @param defaultWriteTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultWriteTimeout(Duration defaultWriteTimeout) {
        return defaultWriteTimeoutMillis(requireNonNull(defaultWriteTimeout, "defaultWriteTimeout").toMillis());
    }

    /**
     * Sets the default timeout of a response in milliseconds.
     *
     * @param defaultResponseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultResponseTimeoutMillis(long defaultResponseTimeoutMillis) {
        return option(ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS, defaultResponseTimeoutMillis);
    }

    /**
     * Sets the default timeout of a response.
     *
     * @param defaultResponseTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultResponseTimeout(Duration defaultResponseTimeout) {
        return defaultResponseTimeoutMillis(
                requireNonNull(defaultResponseTimeout, "defaultResponseTimeout").toMillis());
    }

    /**
     * Adds the specified {@code decorator}.
     */
    public <T extends Client<? super I, ? extends O>, R extends Client<I, O>,
            I extends Request, O extends Response>
    ClientBuilder decorator(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {

        if (options.containsKey(ClientOption.DECORATION)) {
            throw new IllegalArgumentException(
                    "decorator() and option(" + ClientOption.DECORATION + ") are mutually exclusive.");
        }

        if (decoration == null) {
            decoration = new ClientDecorationBuilder();
        }

        decoration.add(requestType, responseType, decorator);
        return this;
    }

    /**
     * Creates a new client which implements the specified {@code clientType}.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link #ClientBuilder(String)} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     */
    @SuppressWarnings("unchecked")
    public <T> T build(Class<T> clientType) {
        requireNonNull(clientType, "clientType");

        if (decoration != null) {
            options.put(ClientOption.DECORATION, ClientOption.DECORATION.newValue(decoration.build()));
        }

        return factory.newClient(uri, clientType, ClientOptions.of(options.values()));
    }
}
