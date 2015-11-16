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

import static com.linecorp.armeria.client.ClientOption.CLIENT_CODEC_DECORATOR;
import static com.linecorp.armeria.client.ClientOption.HTTP_HEADERS;
import static com.linecorp.armeria.client.ClientOption.INVOCATION_HANDLER_DECORATOR;
import static com.linecorp.armeria.client.ClientOption.REMOTE_INVOKER_DECORATOR;
import static com.linecorp.armeria.client.ClientOption.RESPONSE_TIMEOUT_POLICY;
import static com.linecorp.armeria.client.ClientOption.WRITE_TIMEOUT_POLICY;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationHandler;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.linecorp.armeria.common.TimeoutPolicy;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;

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
    private static final Set<CharSequence> BLACKLISTED_HEADER_NAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.<CharSequence>asList(HttpHeaderNames.HOST,
                                                      HttpHeaderNames.USER_AGENT,
                                                      HttpHeaderNames.CONNECTION,
                                                      HttpHeaderNames.CONTENT_TYPE,
                                                      HttpHeaderNames.ACCEPT,
                                                      HttpHeaderNames.KEEP_ALIVE,
                                                      HttpHeaderNames.PROXY_CONNECTION,
                                                      HttpHeaderNames.TRANSFER_ENCODING,
                                                      HttpHeaderNames.UPGRADE,
                                                      ExtensionHeaderNames.SCHEME.text(),
                                                      ExtensionHeaderNames.PATH.text(),
                                                      ExtensionHeaderNames.STREAM_ID.text())));

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(ClientOptionValue<?>... options) {
        if (options == null || options.length == 0) {
            return DEFAULT;
        }
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

    private static <T> ClientOptionValue<T> validateValue(ClientOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");

        ClientOption<?> option = optionValue.option();
        T value = optionValue.value();

        if (option == HTTP_HEADERS) {
            validateHttpHeaders((HttpHeaders) value);
        }
        return optionValue;
    }

    private static void validateHttpHeaders(HttpHeaders headers) {
        requireNonNull(headers, "headers");
        BLACKLISTED_HEADER_NAMES.stream().filter(headers::contains).anyMatch(h -> {
            throw new IllegalArgumentException("unallowed header name: " + h);
        });
    }

    private ClientOptions(ClientOptionValue<?>... options) {
        this(null, options);
    }

    private ClientOptions(ClientOptions clientOptions, ClientOptionValue<?>... options) {
        super(clientOptions, ClientOptions::validateValue, options);
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
     * Returns the {@link Function} that decorates the {@link InvocationHandler}.
     */
    public Function<InvocationHandler, InvocationHandler> invocationHandlerDecorator() {
        return getOrElse(INVOCATION_HANDLER_DECORATOR, Function.identity());
    }

    /**
     * The {@link Function} that decorates the {@link ClientCodec}.
     */
    public Function<ClientCodec, ClientCodec> clientCodecDecorator() {
        return getOrElse(CLIENT_CODEC_DECORATOR, Function.identity());
    }

    /**
     * Returns the {@link Function} that decorates the {@link RemoteInvoker}.
     */
    public Function<RemoteInvoker, RemoteInvoker> remoteInvokerDecorator() {
        return getOrElse(REMOTE_INVOKER_DECORATOR, Function.identity());
    }
}

