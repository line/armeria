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

import static com.linecorp.armeria.client.ClientOption.DECORATION;
import static com.linecorp.armeria.client.ClientOption.DEFAULT_MAX_RESPONSE_LENGTH;
import static com.linecorp.armeria.client.ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS;
import static com.linecorp.armeria.client.ClientOption.DEFAULT_WRITE_TIMEOUT_MILLIS;
import static com.linecorp.armeria.client.ClientOption.HTTP_HEADERS;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

/**
 * A set of {@link ClientOption}s and their respective values.
 */
public final class ClientOptions extends AbstractOptions {

    private static final Long DEFAULT_DEFAULT_WRITE_TIMEOUT_MILLIS = Duration.ofSeconds(1).toMillis();

    @SuppressWarnings("deprecation")
    private static final Collection<AsciiString> BLACKLISTED_HEADER_NAMES =
            Collections.unmodifiableCollection(Arrays.asList(
                    HttpHeaderNames.AUTHORITY,
                    HttpHeaderNames.CONNECTION,
                    HttpHeaderNames.HOST,
                    HttpHeaderNames.KEEP_ALIVE,
                    HttpHeaderNames.METHOD,
                    HttpHeaderNames.PATH,
                    HttpHeaderNames.PROXY_CONNECTION,
                    HttpHeaderNames.SCHEME,
                    HttpHeaderNames.STATUS,
                    HttpHeaderNames.TRANSFER_ENCODING,
                    HttpHeaderNames.UPGRADE,
                    ExtensionHeaderNames.PATH.text(),
                    ExtensionHeaderNames.SCHEME.text(),
                    ExtensionHeaderNames.STREAM_DEPENDENCY_ID.text(),
                    ExtensionHeaderNames.STREAM_ID.text(),
                    ExtensionHeaderNames.STREAM_PROMISE_ID.text()));

    private static final ClientOptionValue<?>[] DEFAULT_OPTIONS = {
            DEFAULT_WRITE_TIMEOUT_MILLIS.newValue(DEFAULT_DEFAULT_WRITE_TIMEOUT_MILLIS),
            DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(Flags.defaultResponseTimeoutMillis()),
            DEFAULT_MAX_RESPONSE_LENGTH.newValue(Flags.defaultMaxResponseLength()),
            DECORATION.newValue(ClientDecoration.NONE),
            HTTP_HEADERS.newValue(HttpHeaders.EMPTY_HEADERS)
    };

    /**
     * The default {@link ClientOptions}.
     */
    public static final ClientOptions DEFAULT = new ClientOptions(DEFAULT_OPTIONS);

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        if (options.length == 0) {
            return DEFAULT;
        }
        return new ClientOptions(DEFAULT, options);
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(Iterable<ClientOptionValue<?>> options) {
        return new ClientOptions(DEFAULT, options);
    }

    /**
     * Merges the specified {@link ClientOptions} and {@link ClientOptionValue}s.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions, ClientOptionValue<?>... options) {
        // TODO(trustin): Reduce the cost of creating a derived ClientOptions.
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");
        if (options.length == 0) {
            return baseOptions;
        }
        return new ClientOptions(baseOptions, options);
    }

    /**
     * Merges the specified {@link ClientOptions} and {@link ClientOptionValue}s.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions, Iterable<ClientOptionValue<?>> options) {
        // TODO(trustin): Reduce the cost of creating a derived ClientOptions.
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");
        return new ClientOptions(baseOptions, options);
    }

    /**
     * Merges the specified two {@link ClientOptions} into one.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions, ClientOptions options) {
        // TODO(trustin): Reduce the cost of creating a derived ClientOptions.
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");
        return new ClientOptions(baseOptions, options);
    }

    private static <T> ClientOptionValue<T> filterValue(ClientOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");

        ClientOption<?> option = optionValue.option();
        T value = optionValue.value();

        if (option == HTTP_HEADERS) {
            @SuppressWarnings("unchecked")
            ClientOption<HttpHeaders> castOption = (ClientOption<HttpHeaders>) option;
            @SuppressWarnings("unchecked")
            ClientOptionValue<T> castOptionValue =
                    (ClientOptionValue<T>) castOption.newValue(filterHttpHeaders((HttpHeaders) value));
            optionValue = castOptionValue;
        }
        return optionValue;
    }

    private static HttpHeaders filterHttpHeaders(HttpHeaders headers) {
        requireNonNull(headers, "headers");
        for (AsciiString name : BLACKLISTED_HEADER_NAMES) {
            if (headers.contains(name)) {
                throw new IllegalArgumentException("unallowed header name: " + name);
            }
        }

        return new DefaultHttpHeaders().add(headers).asImmutable();
    }

    private ClientOptions(ClientOptionValue<?>... options) {
        super(ClientOptions::filterValue, options);
    }

    private ClientOptions(ClientOptions clientOptions, ClientOptionValue<?>... options) {
        super(ClientOptions::filterValue, clientOptions, options);
    }

    private ClientOptions(ClientOptions clientOptions, Iterable<ClientOptionValue<?>> options) {
        super(ClientOptions::filterValue, clientOptions, options);
    }

    private ClientOptions(ClientOptions clientOptions, ClientOptions options) {
        super(clientOptions, options);
    }

    /**
     * Returns the value of the specified {@link ClientOption}.
     *
     * @return the value of the {@link ClientOption}, or
     *         {@link Optional#empty()} if the default value of the specified {@link ClientOption} is not
     *         available
     */
    public <T> Optional<T> get(ClientOption<T> option) {
        return get0(option);
    }

    /**
     * Returns the value of the specified {@link ClientOption}.
     *
     * @return the value of the {@link ClientOption}, or
     *         {@code defaultValue} if the specified {@link ClientOption} is not set.
     */
    public <T> T getOrElse(ClientOption<T> option, T defaultValue) {
        return getOrElse0(option, defaultValue);
    }

    /**
     * Converts this {@link ClientOptions} to a {@link Map}.
     */
    public Map<ClientOption<Object>, ClientOptionValue<Object>> asMap() {
        return asMap0();
    }

    /**
     * Returns the default timeout of a server reply to a client call.
     */
    public long defaultResponseTimeoutMillis() {
        return getOrElse(DEFAULT_RESPONSE_TIMEOUT_MILLIS, Flags.defaultResponseTimeoutMillis());
    }

    /**
     * Returns the default timeout of a socket write.
     */
    public long defaultWriteTimeoutMillis() {
        return getOrElse(DEFAULT_WRITE_TIMEOUT_MILLIS, DEFAULT_DEFAULT_WRITE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the maximum allowed length of a server response.
     */
    @SuppressWarnings("unchecked")
    public long defaultMaxResponseLength() {
        return getOrElse(DEFAULT_MAX_RESPONSE_LENGTH, Flags.defaultMaxResponseLength());
    }

    /**
     * Returns the {@link Function}s that decorate the components of a client.
     */
    public ClientDecoration decoration() {
        return getOrElse(DECORATION, ClientDecoration.NONE);
    }

    /**
     * Returns the additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public HttpHeaders httpHeaders() {
        return getOrElse(HTTP_HEADERS, HttpHeaders.EMPTY_HEADERS);
    }
}
