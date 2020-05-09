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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.util.AbstractOption;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

/**
 * A client option.
 *
 * @param <T> the type of the option value
 */
public final class ClientOption<T> extends AbstractOption<ClientOption<T>, ClientOptionValue<T>, T> {

    /**
     * The {@link ClientFactory} used for creating a client.
     */
    public static final ClientOption<ClientFactory> FACTORY =
            define("FACTORY", ClientFactory.ofDefault());

    /**
     * The timeout of a socket write.
     */
    public static final ClientOption<Long> WRITE_TIMEOUT_MILLIS =
            define("WRITE_TIMEOUT_MILLIS", Flags.defaultWriteTimeoutMillis());

    /**
     * The timeout of a server reply to a client call.
     */
    public static final ClientOption<Long> RESPONSE_TIMEOUT_MILLIS =
            define("RESPONSE_TIMEOUT_MILLIS", Flags.defaultResponseTimeoutMillis());

    /**
     * The maximum allowed length of a server response.
     */
    public static final ClientOption<Long> MAX_RESPONSE_LENGTH =
            define("MAX_RESPONSE_LENGTH", Flags.defaultMaxResponseLength());

    private static final List<AsciiString> BLACKLISTED_HEADER_NAMES = ImmutableList.of(
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
            ExtensionHeaderNames.STREAM_PROMISE_ID.text());

    /**
     * The additional HTTP headers to send with requests.
     */
    public static final ClientOption<HttpHeaders> HTTP_HEADERS =
            define("HTTP_HEADERS", HttpHeaders.of(), newHeaders -> {
                for (AsciiString name : BLACKLISTED_HEADER_NAMES) {
                    if (newHeaders.contains(name)) {
                        throw new IllegalArgumentException("prohibited header name: " + name);
                    }
                }
                return newHeaders;
            }, (oldValue, newValue) -> {
                final HttpHeaders newHeaders = newValue.value();
                if (newHeaders.isEmpty()) {
                    return oldValue;
                }
                final HttpHeaders oldHeaders = oldValue.value();
                if (oldHeaders.isEmpty()) {
                    return newValue;
                }
                return newValue.option().newValue(oldHeaders.toBuilder().set(newHeaders).build());
            });

    /**
     * The {@link Function} that decorates the client components.
     */
    public static final ClientOption<ClientDecoration> DECORATION =
            define("DECORATION", ClientDecoration.of(), Function.identity(), (oldValue, newValue) -> {
                final ClientDecoration newDecoration = newValue.value();
                if (newDecoration.isEmpty()) {
                    return oldValue;
                }
                final ClientDecoration oldDecoration = oldValue.value();
                if (oldDecoration.isEmpty()) {
                    return newValue;
                }
                return newValue.option().newValue(ClientDecoration.builder()
                                                                  .add(oldDecoration)
                                                                  .add(newDecoration)
                                                                  .build());
            });

    /**
     * The {@link Supplier} that generates a {@link RequestId}.
     */
    public static final ClientOption<Supplier<RequestId>> REQUEST_ID_GENERATOR = define(
            "REQUEST_ID_GENERATOR", RequestId::random);

    /**
     * A {@link Function} that remaps a target {@link Endpoint} into an {@link EndpointGroup}.
     *
     * @see ClientBuilder#endpointRemapper(Function)
     */
    public static final ClientOption<Function<? super Endpoint, ? extends EndpointGroup>> ENDPOINT_REMAPPER =
            define("ENDPOINT_REMAPPER", Function.identity());

    /**
     * Returns the all available {@link ClientOption}s.
     */
    public static Set<ClientOption<?>> allOptions() {
        return allOptions(ClientOption.class);
    }

    /**
     * Returns the {@link ClientOption} with the specified {@code name}.
     *
     * @throws NoSuchElementException if there's no such option defined.
     */
    public static ClientOption<?> of(String name) {
        return of(ClientOption.class, name);
    }

    /**
     * Defines a new {@link ClientOption} of the specified name and default value.
     *
     * @param name the name of the option.
     * @param defaultValue the default value of the option, which will be used when unspecified.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    public static <T> ClientOption<T> define(String name, T defaultValue) {
        return define(name, defaultValue, Function.identity(), (oldValue, newValue) -> newValue);
    }

    /**
     * Defines a new {@link ClientOption} of the specified name, default value and merge function.
     *
     * @param name the name of the option.
     * @param defaultValue the default value of the option, which will be used when unspecified.
     * @param validator the {@link Function} which is used for validating and normalizing an option value.
     * @param mergeFunction the {@link BiFunction} which is used for merging old and new option values.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    public static <T> ClientOption<T> define(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ClientOptionValue<T>, ClientOptionValue<T>, ClientOptionValue<T>> mergeFunction) {
        return define(ClientOption.class, name, defaultValue, ClientOption::new, validator, mergeFunction);
    }

    private ClientOption(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ClientOptionValue<T>, ClientOptionValue<T>, ClientOptionValue<T>> mergeFunction) {
        super(name, defaultValue, validator, mergeFunction);
    }

    @Override
    protected ClientOptionValue<T> doNewValue(T value) {
        return new ClientOptionValue<>(this, value);
    }
}
