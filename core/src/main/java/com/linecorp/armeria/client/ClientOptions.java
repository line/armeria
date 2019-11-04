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
import static com.linecorp.armeria.client.ClientOption.HTTP_HEADERS;
import static com.linecorp.armeria.client.ClientOption.MAX_RESPONSE_LENGTH;
import static com.linecorp.armeria.client.ClientOption.REQ_CONTENT_PREVIEWER_FACTORY;
import static com.linecorp.armeria.client.ClientOption.RESPONSE_TIMEOUT_MILLIS;
import static com.linecorp.armeria.client.ClientOption.RES_CONTENT_PREVIEWER_FACTORY;
import static com.linecorp.armeria.client.ClientOption.WRITE_TIMEOUT_MILLIS;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.AbstractOptions;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

/**
 * A set of {@link ClientOption}s and their respective values.
 */
public final class ClientOptions extends AbstractOptions {

    private static final Collection<AsciiString> BLACKLISTED_HEADER_NAMES =
            Collections.unmodifiableCollection(Arrays.asList(
                    HttpHeaderNames.CONNECTION,
                    HttpHeaderNames.HOST,
                    HttpHeaderNames.HTTP2_SETTINGS,
                    HttpHeaderNames.METHOD,
                    HttpHeaderNames.PATH,
                    HttpHeaderNames.SCHEME,
                    HttpHeaderNames.STATUS,
                    HttpHeaderNames.TRANSFER_ENCODING,
                    HttpHeaderNames.UPGRADE,
                    ArmeriaHttpUtil.HEADER_NAME_KEEP_ALIVE,
                    ArmeriaHttpUtil.HEADER_NAME_PROXY_CONNECTION,
                    ExtensionHeaderNames.PATH.text(),
                    ExtensionHeaderNames.SCHEME.text(),
                    ExtensionHeaderNames.STREAM_DEPENDENCY_ID.text(),
                    ExtensionHeaderNames.STREAM_ID.text(),
                    ExtensionHeaderNames.STREAM_PROMISE_ID.text()));

    private static final ClientOptionValue<?>[] DEFAULT_OPTIONS = {
            WRITE_TIMEOUT_MILLIS.newValue(Flags.defaultWriteTimeoutMillis()),
            RESPONSE_TIMEOUT_MILLIS.newValue(Flags.defaultResponseTimeoutMillis()),
            MAX_RESPONSE_LENGTH.newValue(Flags.defaultMaxResponseLength()),
            DECORATION.newValue(ClientDecoration.of()),
            HTTP_HEADERS.newValue(HttpHeaders.of())
    };

    /**
     * The default {@link ClientOptions}.
     *
     * @deprecated Use {@link #of()}.
     */
    @Deprecated
    public static final ClientOptions DEFAULT = new ClientOptions(DEFAULT_OPTIONS);

    /**
     * Returns the {@link ClientOptions} with the default options only.
     */
    public static ClientOptions of() {
        return DEFAULT;
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(ClientOptionValue<?>... options) {
        return of(of(), requireNonNull(options, "options"));
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(Iterable<ClientOptionValue<?>> options) {
        return of(of(), requireNonNull(options, "options"));
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
        if (Iterables.isEmpty(options)) {
            return baseOptions;
        }
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

    /**
     * Returns a newly created {@link ClientOptionsBuilder}.
     */
    public static ClientOptionsBuilder builder() {
        return new ClientOptionsBuilder();
    }

    private static <T> ClientOptionValue<T> filterValue(ClientOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");

        final ClientOption<?> option = optionValue.option();
        final T value = optionValue.value();

        if (option == HTTP_HEADERS) {
            @SuppressWarnings("unchecked")
            final ClientOption<HttpHeaders> castOption = (ClientOption<HttpHeaders>) option;
            @SuppressWarnings("unchecked")
            final ClientOptionValue<T> castOptionValue =
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

        return headers;
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
     * Returns the timeout of a socket write.
     *
     * @deprecated Use {@link #writeTimeoutMillis()}.
     */
    @Deprecated
    public long defaultWriteTimeoutMillis() {
        return writeTimeoutMillis();
    }

    /**
     * Returns the timeout of a socket write.
     */
    public long writeTimeoutMillis() {
        return getOrElse(WRITE_TIMEOUT_MILLIS, Flags.defaultWriteTimeoutMillis());
    }

    /**
     * Returns the timeout of a server reply to a client call.
     *
     * @deprecated Use {@link #responseTimeoutMillis()}.
     */
    @Deprecated
    public long defaultResponseTimeoutMillis1() {
        return responseTimeoutMillis();
    }

    /**
     * Returns the timeout of a server reply to a client call.
     */
    public long responseTimeoutMillis() {
        return getOrElse(RESPONSE_TIMEOUT_MILLIS, Flags.defaultResponseTimeoutMillis());
    }

    /**
     * Returns the maximum allowed length of a server response.
     *
     * @deprecated Use {@link #maxResponseLength()}.
     */
    @Deprecated
    public long defaultMaxResponseLength() {
        return maxResponseLength();
    }

    /**
     * Returns the maximum allowed length of a server response.
     */
    public long maxResponseLength() {
        return getOrElse(MAX_RESPONSE_LENGTH, Flags.defaultMaxResponseLength());
    }

    /**
     * Returns the {@link Function}s that decorate the components of a client.
     */
    public ClientDecoration decoration() {
        return getOrElse(DECORATION, ClientDecoration.of());
    }

    /**
     * Returns the additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public HttpHeaders httpHeaders() {
        return getOrElse(HTTP_HEADERS, HttpHeaders.of());
    }

    /**
     * Returns the request {@link ContentPreviewerFactory}.
     */
    public ContentPreviewerFactory requestContentPreviewerFactory() {
        return getOrElse(REQ_CONTENT_PREVIEWER_FACTORY, ContentPreviewerFactory.disabled());
    }

    /**
     * Returns the response {@link ContentPreviewerFactory}.
     */
    public ContentPreviewerFactory responseContentPreviewerFactory() {
        return getOrElse(RES_CONTENT_PREVIEWER_FACTORY, ContentPreviewerFactory.disabled());
    }

    /**
     * Returns a new {@link ClientOptionsBuilder} created from this {@link ClientOptions}.
     */
    public ClientOptionsBuilder toBuilder() {
        return new ClientOptionsBuilder(this);
    }
}
