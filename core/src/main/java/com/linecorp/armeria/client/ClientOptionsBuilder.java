/*
 * Copyright 2016 LINE Corporation
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

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaders;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * Creates a new {@link ClientOptions} using the builder pattern.
 *
 * <h3>{@link ClientOption#DECORATION} and {@link ClientOption#HTTP_HEADERS}</h3>
 * Unlike other options, when a user calls {@link #option(ClientOption, Object)} or {@code options()} with
 * a {@link ClientOption#DECORATION} or a {@link ClientOption#HTTP_HEADERS}, this builder will not simply
 * replace the old option but <em>merge</em> the specified option into the previous option value. For example:
 * <pre>{@code
 * ClientOptionsBuilder b = new ClientOptionsBuilder();
 * b.option(ClientOption.HTTP_HEADERS, headersA);
 * b.option(ClientOption.HTTP_HEADERS, headersB);
 * b.option(ClientOption.DECORATION, decorationA);
 * b.option(ClientOption.DECORATION, decorationB);
 *
 * ClientOptions opts = b.build();
 * HttpHeaders httpHeaders = opts.httpHeaders();
 * ClientDecoration decorations = opts.decoration();
 * }</pre>
 * {@code httpHeaders} will contain all HTTP headers of {@code headersA} and {@code headersB}.
 * If {@code headersA} and {@code headersB} have the headers with the same name, the duplicate header in
 * {@code headerB} will replace the one with the same name in {@code headerA}.
 * Similarly, {@code decorations} will contain all decorators of {@code decorationA} and {@code decorationB},
 * but there will be no replacement but only addition.
 */
public final class ClientOptionsBuilder {

    private final Map<ClientOption<?>, ClientOptionValue<?>> options = new LinkedHashMap<>();
    private final ClientDecorationBuilder decoration = new ClientDecorationBuilder();
    private final HttpHeaders httpHeaders = new DefaultHttpHeaders();

    /**
     * Creates a new instance with the default options.
     */
    public ClientOptionsBuilder() {}

    /**
     * Creates a new instance with the specified base options.
     */
    public ClientOptionsBuilder(ClientOptions options) {
        requireNonNull(options, "options");
        options(options);
    }

    /**
     * Adds the specified {@link ClientOptions}.
     */
    public ClientOptionsBuilder options(ClientOptions options) {
        requireNonNull(options, "options");
        options.asMap().values().forEach(this::option);
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public ClientOptionsBuilder options(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        for (ClientOptionValue<?> o : options) {
            option(o);
        }
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public ClientOptionsBuilder options(Iterable<ClientOptionValue<?>> options) {
        requireNonNull(options, "options");
        options(Iterables.toArray(options, ClientOptionValue.class));
        return this;
    }

    /**
     * Adds the specified {@link ClientOption} and its {@code value}.
     */
    public <T> ClientOptionsBuilder option(ClientOption<T> option, T value) {
        requireNonNull(option, "option");
        requireNonNull(value, "value");
        return option(option.newValue(value));
    }

    /**
     * Adds the specified {@link ClientOptionValue}.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> ClientOptionsBuilder option(ClientOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");
        final ClientOption<?> opt = optionValue.option();
        if (opt == ClientOption.DECORATION) {
            final ClientDecoration d = (ClientDecoration) optionValue.value();
            d.entries().forEach(e -> decorator((Class) e.requestType(), e.responseType(),
                                               (Function) e.decorator()));
        } else if (opt == ClientOption.HTTP_HEADERS) {
            final HttpHeaders h = (HttpHeaders) optionValue.value();
            setHttpHeaders(h);
        } else {
            options.put(opt, optionValue);
        }
        return this;
    }

    /**
     * Sets the default timeout of a socket write attempt in milliseconds.
     *
     * @param defaultWriteTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientOptionsBuilder defaultWriteTimeoutMillis(long defaultWriteTimeoutMillis) {
        return option(ClientOption.DEFAULT_WRITE_TIMEOUT_MILLIS, defaultWriteTimeoutMillis);
    }

    /**
     * Sets the default timeout of a socket write attempt.
     *
     * @param defaultWriteTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientOptionsBuilder defaultWriteTimeout(Duration defaultWriteTimeout) {
        return defaultWriteTimeoutMillis(requireNonNull(defaultWriteTimeout, "defaultWriteTimeout").toMillis());
    }

    /**
     * Sets the default timeout of a response in milliseconds.
     *
     * @param defaultResponseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientOptionsBuilder defaultResponseTimeoutMillis(long defaultResponseTimeoutMillis) {
        return option(ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS, defaultResponseTimeoutMillis);
    }

    /**
     * Sets the default timeout of a response.
     *
     * @param defaultResponseTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientOptionsBuilder defaultResponseTimeout(Duration defaultResponseTimeout) {
        return defaultResponseTimeoutMillis(
                requireNonNull(defaultResponseTimeout, "defaultResponseTimeout").toMillis());
    }

    /**
     * Sets the default maximum allowed length of a server response in bytes.
     *
     * @param defaultMaxResponseLength the maximum length in bytes. {@code 0} disables the limit.
     */
    public ClientOptionsBuilder defaultMaxResponseLength(long defaultMaxResponseLength) {
        return option(ClientOption.DEFAULT_MAX_RESPONSE_LENGTH, defaultMaxResponseLength);
    }

    /**
     * Adds the specified {@code decorator}.
     */
    public <T extends Client<? super I, ? extends O>, R extends Client<I, O>,
            I extends Request, O extends Response>
    ClientOptionsBuilder decorator(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {
        decoration.add(requestType, responseType, decorator);
        return this;
    }

    /**
     * Adds the specified HTTP header.
     */
    public ClientOptionsBuilder addHttpHeader(AsciiString name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        httpHeaders.addObject(name, value);
        return this;
    }

    /**
     * Adds the specified HTTP headers.
     */
    public ClientOptionsBuilder addHttpHeaders(Headers<AsciiString, String, ?> httpHeaders) {
        requireNonNull(httpHeaders, "httpHeaders");
        this.httpHeaders.add(httpHeaders);
        return this;
    }

    /**
     * Sets the specified HTTP header.
     */
    public ClientOptionsBuilder setHttpHeader(AsciiString name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        httpHeaders.setObject(name, value);
        return this;
    }

    /**
     * Sets the specified HTTP headers.
     */
    public ClientOptionsBuilder setHttpHeaders(Headers<AsciiString, String, ?> httpHeaders) {
        requireNonNull(httpHeaders, "httpHeaders");
        this.httpHeaders.setAll(httpHeaders);
        return this;
    }

    /**
     * Creates a new {@link ClientOptions}.
     */
    public ClientOptions build() {
        final Collection<ClientOptionValue<?>> optVals = options.values();
        final int numOpts = optVals.size();
        ClientOptionValue<?>[] optValArray = optVals.toArray(new ClientOptionValue[numOpts + 2]);
        optValArray[numOpts] = ClientOption.DECORATION.newValue(decoration.build());
        optValArray[numOpts + 1] = ClientOption.HTTP_HEADERS.newValue(httpHeaders);

        return ClientOptions.of(optValArray);
    }
}
