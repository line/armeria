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

import static com.linecorp.armeria.client.ClientOption.DECORATOR;
import static com.linecorp.armeria.client.ClientOption.HTTP_HEADERS;
import static com.linecorp.armeria.client.ClientOption.RESPONSE_TIMEOUT_POLICY;
import static com.linecorp.armeria.client.ClientOption.WRITE_TIMEOUT_POLICY;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.linecorp.armeria.common.TimeoutPolicy;
import com.linecorp.armeria.common.http.ImmutableHttpHeaders;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

/**
 * A set of {@link ClientOption}s and their respective values.
 */
public final class ClientOptions extends AbstractOptions {

    private static final TimeoutPolicy DEFAULT_WRITE_TIMEOUT_POLICY =
            TimeoutPolicy.ofFixed(Duration.ofSeconds(1));
    private static final TimeoutPolicy DEFAULT_RESPONSE_TIMEOUT_POLICY =
            TimeoutPolicy.ofFixed(Duration.ofSeconds(10));

    private static final ClientOptionValue<?>[] DEFAULT_OPTIONS = {
            WRITE_TIMEOUT_POLICY.newValue(DEFAULT_WRITE_TIMEOUT_POLICY),
            RESPONSE_TIMEOUT_POLICY.newValue(DEFAULT_RESPONSE_TIMEOUT_POLICY)
    };

    /**
     * The default {@link ClientOptions}.
     */
    public static final ClientOptions DEFAULT = new ClientOptions(DEFAULT_OPTIONS);

    @SuppressWarnings("deprecation")
    private static final Collection<AsciiString> BLACKLISTED_HEADER_NAMES =
            Collections.unmodifiableCollection(Arrays.asList(
                    HttpHeaderNames.CONNECTION,
                    HttpHeaderNames.HOST,
                    HttpHeaderNames.KEEP_ALIVE,
                    HttpHeaderNames.PROXY_CONNECTION,
                    HttpHeaderNames.TRANSFER_ENCODING,
                    HttpHeaderNames.UPGRADE,
                    HttpHeaderNames.USER_AGENT,
                    ExtensionHeaderNames.PATH.text(),
                    ExtensionHeaderNames.SCHEME.text(),
                    ExtensionHeaderNames.STREAM_DEPENDENCY_ID.text(),
                    ExtensionHeaderNames.STREAM_ID.text(),
                    ExtensionHeaderNames.STREAM_PROMISE_ID.text()));

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
        BLACKLISTED_HEADER_NAMES.stream().filter(headers::contains).anyMatch(h -> {
            throw new IllegalArgumentException("unallowed header name: " + h);
        });

        // Create an immutable copy to prevent further modification.
        return new ImmutableHttpHeaders(new DefaultHttpHeaders(false).add(headers));
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
     * Returns the {@link TimeoutPolicy} for a server reply to a client call.
     */
    public TimeoutPolicy responseTimeoutPolicy() {
        return getOrElse(RESPONSE_TIMEOUT_POLICY, DEFAULT_RESPONSE_TIMEOUT_POLICY);
    }

    /**
     * Returns the {@link TimeoutPolicy} for a socket write.
     */
    public TimeoutPolicy writeTimeoutPolicy() {
        return getOrElse(WRITE_TIMEOUT_POLICY, DEFAULT_WRITE_TIMEOUT_POLICY);
    }

    /**
     * Returns the {@link Function} that decorates the components of a client.
     */
    public Function<Client, Client> decorator() {
        return getOrElse(DECORATOR, Function.identity());
    }
}

