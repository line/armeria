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

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AbstractOptions;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

/**
 * A set of {@link ClientOption}s and their respective values.
 */
public final class ClientOptions
        extends AbstractOptions<ClientOption<Object>, ClientOptionValue<Object>> {

    /**
     * The {@link ClientFactory} used for creating a client.
     */
    public static final ClientOption<ClientFactory> FACTORY =
            ClientOption.define("FACTORY", ClientFactory.ofDefault());

    /**
     * The timeout of a socket write.
     */
    public static final ClientOption<Long> WRITE_TIMEOUT_MILLIS =
            ClientOption.define("WRITE_TIMEOUT_MILLIS", Flags.defaultWriteTimeoutMillis());

    /**
     * The timeout of a server reply to a client call.
     */
    public static final ClientOption<Long> RESPONSE_TIMEOUT_MILLIS =
            ClientOption.define("RESPONSE_TIMEOUT_MILLIS", Flags.defaultResponseTimeoutMillis());

    /**
     * The maximum allowed length of a server response.
     */
    public static final ClientOption<Long> MAX_RESPONSE_LENGTH =
            ClientOption.define("MAX_RESPONSE_LENGTH", Flags.defaultMaxResponseLength());

    /**
     * The redirect configuration.
     */
    @UnstableApi
    public static final ClientOption<RedirectConfig> REDIRECT_CONFIG =
            ClientOption.define("REDIRECT_CONFIG", RedirectConfig.disabled());

    /**
     * The {@link Function} that decorates the client components.
     */
    public static final ClientOption<ClientDecoration> DECORATION =
            ClientOption.define("DECORATION", ClientDecoration.of(), Function.identity(),
                                (oldValue, newValue) -> {
                                    final ClientDecoration newDecoration = newValue.value();
                                    if (newDecoration.isEmpty()) {
                                        return oldValue;
                                    }
                                    final ClientDecoration oldDecoration = oldValue.value();
                                    if (oldDecoration.isEmpty()) {
                                        return newValue;
                                    }
                                    return newValue.option().newValue(
                                            ClientDecoration.builder()
                                                            .add(oldDecoration)
                                                            .add(newDecoration)
                                                            .build());
                                });

    /**
     * The {@link Supplier} that generates a {@link RequestId}.
     */
    public static final ClientOption<Supplier<RequestId>> REQUEST_ID_GENERATOR = ClientOption.define(
            "REQUEST_ID_GENERATOR", RequestId::random);

    /**
     * A {@link Function} that remaps a target {@link Endpoint} into an {@link EndpointGroup}.
     *
     * @see ClientBuilder#endpointRemapper(Function)
     */
    public static final ClientOption<Function<? super Endpoint, ? extends EndpointGroup>> ENDPOINT_REMAPPER =
            ClientOption.define("ENDPOINT_REMAPPER", Function.identity());

    private static final List<AsciiString> PROHIBITED_HEADER_NAMES = ImmutableList.of(
            HttpHeaderNames.CONNECTION,
            HttpHeaderNames.HOST,
            HttpHeaderNames.HTTP2_SETTINGS,
            HttpHeaderNames.METHOD,
            HttpHeaderNames.PATH,
            HttpHeaderNames.SCHEME,
            HttpHeaderNames.STATUS,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.UPGRADE,
            HttpHeaderNames.KEEP_ALIVE,
            ArmeriaHttpUtil.HEADER_NAME_PROXY_CONNECTION,
            ExtensionHeaderNames.PATH.text(),
            ExtensionHeaderNames.SCHEME.text(),
            ExtensionHeaderNames.STREAM_DEPENDENCY_ID.text(),
            ExtensionHeaderNames.STREAM_ID.text(),
            ExtensionHeaderNames.STREAM_PROMISE_ID.text());

    /**
     * The additional HTTP headers to send with requests.
     */
    public static final ClientOption<HttpHeaders> HEADERS =
            ClientOption.define("HEADERS", HttpHeaders.of(), newHeaders -> {
                for (AsciiString name : PROHIBITED_HEADER_NAMES) {
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

    private static final ClientOptions EMPTY = new ClientOptions(ImmutableList.of());

    /**
     * Returns an empty singleton {@link ClientOptions}.
     */
    public static ClientOptions of() {
        return EMPTY;
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(ClientOptionValue<?>... values) {
        requireNonNull(values, "values");
        if (values.length == 0) {
            return EMPTY;
        }
        return of(Arrays.asList(values));
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(Iterable<? extends ClientOptionValue<?>> values) {
        requireNonNull(values, "values");
        if (values instanceof ClientOptions) {
            return (ClientOptions) values;
        }
        return new ClientOptions(values);
    }

    /**
     * Merges the specified {@link ClientOptions} and {@link ClientOptionValue}s.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions, ClientOptionValue<?>... additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");
        if (additionalValues.length == 0) {
            return baseOptions;
        }
        return new ClientOptions(baseOptions, Arrays.asList(additionalValues));
    }

    /**
     * Merges the specified {@link ClientOptions} and {@link ClientOptionValue}s.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions,
                                   Iterable<? extends ClientOptionValue<?>> additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");
        return new ClientOptions(baseOptions, additionalValues);
    }

    /**
     * Returns a newly created {@link ClientOptionsBuilder}.
     */
    public static ClientOptionsBuilder builder() {
        return new ClientOptionsBuilder();
    }

    private ClientOptions(Iterable<? extends ClientOptionValue<?>> values) {
        super(values);
    }

    private ClientOptions(ClientOptions baseOptions,
                          Iterable<? extends ClientOptionValue<?>> additionalValues) {
        super(baseOptions, additionalValues);
    }

    /**
     * Returns the {@link ClientFactory} used for creating a client.
     */
    public ClientFactory factory() {
        return get(FACTORY);
    }

    /**
     * Returns the timeout of a socket write.
     */
    public long writeTimeoutMillis() {
        return get(WRITE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the timeout of a server reply to a client call.
     */
    public long responseTimeoutMillis() {
        return get(RESPONSE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the maximum allowed length of a server response.
     */
    public long maxResponseLength() {
        return get(MAX_RESPONSE_LENGTH);
    }

    /**
     * Returns the {@link RedirectConfig}.
     */
    @UnstableApi
    public RedirectConfig redirectConfig() {
        return get(REDIRECT_CONFIG);
    }

    /**
     * Returns the {@link Function}s that decorate the components of a client.
     */
    public ClientDecoration decoration() {
        return get(DECORATION);
    }

    /**
     * Returns the additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public HttpHeaders headers() {
        return get(HEADERS);
    }

    /**
     * Returns the {@link Supplier} that generates a {@link RequestId}.
     */
    public Supplier<RequestId> requestIdGenerator() {
        return get(REQUEST_ID_GENERATOR);
    }

    /**
     * Returns the {@link Function} that remaps a target {@link Endpoint} into an {@link EndpointGroup}.
     *
     * @see ClientBuilder#endpointRemapper(Function)
     */
    public Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper() {
        return get(ENDPOINT_REMAPPER);
    }

    /**
     * Returns a new {@link ClientOptionsBuilder} created from this {@link ClientOptions}.
     */
    public ClientOptionsBuilder toBuilder() {
        return new ClientOptionsBuilder(this);
    }
}
