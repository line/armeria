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
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override.
 */
public final class ClientBuilder {

    private final URI uri;
    private ClientFactory factory = ClientFactory.DEFAULT;
    private final ClientOptionsBuilder options = new ClientOptionsBuilder();

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
        this.options.options(options);
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public ClientBuilder options(ClientOptionValue<?>... options) {
        this.options.options(options);
        return this;
    }

    /**
     * Adds the specified {@link ClientOption} and its {@code value}.
     */
    public <T> ClientBuilder option(ClientOption<T> option, T value) {
        this.options.option(option, value);
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}.
     */
    public <T> ClientBuilder option(ClientOptionValue<T> optionValue) {
        options.option(optionValue);
        return this;
    }

    /**
     * Sets the default timeout of a socket write attempt in milliseconds.
     *
     * @param defaultWriteTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultWriteTimeoutMillis(long defaultWriteTimeoutMillis) {
        options.defaultWriteTimeoutMillis(defaultWriteTimeoutMillis);
        return this;
    }

    /**
     * Sets the default timeout of a socket write attempt.
     *
     * @param defaultWriteTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultWriteTimeout(Duration defaultWriteTimeout) {
        options.defaultWriteTimeout(defaultWriteTimeout);
        return this;
    }

    /**
     * Sets the default timeout of a response in milliseconds.
     *
     * @param defaultResponseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultResponseTimeoutMillis(long defaultResponseTimeoutMillis) {
        options.defaultResponseTimeoutMillis(defaultResponseTimeoutMillis);
        return this;
    }

    /**
     * Sets the default timeout of a response.
     *
     * @param defaultResponseTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientBuilder defaultResponseTimeout(Duration defaultResponseTimeout) {
        options.defaultResponseTimeout(defaultResponseTimeout);
        return this;
    }

    /**
     * Sets the default maximum allowed length of a server response in bytes.
     *
     * @param defaultMaxResponseLength the maximum length in bytes. {@code 0} disables the limit.
     */
    public ClientBuilder defaultMaxResponseLength(long defaultMaxResponseLength) {
        options.defaultMaxResponseLength(defaultMaxResponseLength);
        return this;
    }

    /**
     * Adds the specified {@code decorator}.
     */
    public <T extends Client<? super I, ? extends O>, R extends Client<I, O>,
            I extends Request, O extends Response>
    ClientBuilder decorator(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {
        options.decorator(requestType, responseType, decorator);
        return this;
    }

    /**
     * Adds the specified HTTP header.
     */
    public ClientBuilder addHttpHeader(AsciiString name, Object value) {
        options.addHttpHeader(name, value);
        return this;
    }

    /**
     * Adds the specified HTTP headers.
     */
    public ClientBuilder addHttpHeaders(Headers<AsciiString, String, ?> httpHeaders) {
        options.addHttpHeaders(httpHeaders);
        return this;
    }

    /**
     * Sets the specified HTTP header.
     */
    public ClientBuilder setHttpHeader(AsciiString name, Object value) {
        options.setHttpHeader(name, value);
        return this;
    }

    /**
     * Sets the specified HTTP headers.
     */
    public ClientBuilder setHttpHeaders(Headers<AsciiString, String, ?> httpHeaders) {
        options.setHttpHeaders(httpHeaders);
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
        return factory.newClient(uri, clientType, options.build());
    }
}
