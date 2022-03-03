/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.client.ClientOptions.REDIRECT_CONFIG;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.logging.LoggingRpcClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * A skeletal builder implementation for {@link ClientOptions}.
 */
public class AbstractClientOptionsBuilder {

    private final Map<ClientOption<?>, ClientOptionValue<?>> options = new LinkedHashMap<>();
    private final ClientDecorationBuilder decoration = ClientDecoration.builder();
    private final HttpHeadersBuilder headers = HttpHeaders.builder();

    /**
     * Creates a new instance.
     */
    protected AbstractClientOptionsBuilder() {}

    /**
     * Creates a new instance with the specified base options.
     */
    protected AbstractClientOptionsBuilder(ClientOptions options) {
        requireNonNull(options, "options");
        options(options);
    }

    /**
     * Adds the specified {@link ClientOptions}.
     */
    public AbstractClientOptionsBuilder options(ClientOptions options) {
        requireNonNull(options, "options");
        options.forEach(this::option);
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public AbstractClientOptionsBuilder options(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        for (ClientOptionValue<?> o : options) {
            option(o);
        }
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public AbstractClientOptionsBuilder options(Iterable<ClientOptionValue<?>> options) {
        requireNonNull(options, "options");
        for (ClientOptionValue<?> o : options) {
            option(o);
        }
        return this;
    }

    /**
     * Adds the specified {@link ClientOption} and its {@code value}.
     */
    public <T> AbstractClientOptionsBuilder option(ClientOption<T> option, T value) {
        requireNonNull(option, "option");
        requireNonNull(value, "value");
        return option(option.newValue(value));
    }

    /**
     * Adds the specified {@link ClientOptionValue}.
     */
    public <T> AbstractClientOptionsBuilder option(ClientOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");
        final ClientOption<?> opt = optionValue.option();
        if (opt == ClientOptions.DECORATION) {
            decoration.add((ClientDecoration) optionValue.value());
        } else if (opt == ClientOptions.HEADERS) {
            final HttpHeaders h = (HttpHeaders) optionValue.value();
            setHeaders(h);
        } else {
            options.put(opt, optionValue);
        }
        return this;
    }

    /**
     * Sets the {@link ClientFactory} used for creating a client.
     * The default is {@link ClientFactory#ofDefault()}.
     */
    public AbstractClientOptionsBuilder factory(ClientFactory factory) {
        return option(ClientOptions.FACTORY, requireNonNull(factory, "factory"));
    }

    /**
     * Sets the timeout of a socket write attempt.
     *
     * @param writeTimeout the timeout. {@code 0} disables the timeout.
     */
    public AbstractClientOptionsBuilder writeTimeout(Duration writeTimeout) {
        return writeTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    /**
     * Sets the timeout of a socket write attempt in milliseconds.
     *
     * @param writeTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public AbstractClientOptionsBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return option(ClientOptions.WRITE_TIMEOUT_MILLIS, writeTimeoutMillis);
    }

    /**
     * Sets the timeout of a response.
     *
     * @param responseTimeout the timeout. {@code 0} disables the timeout.
     */
    public AbstractClientOptionsBuilder responseTimeout(Duration responseTimeout) {
        return responseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    /**
     * Sets the timeout of a response in milliseconds.
     *
     * @param responseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public AbstractClientOptionsBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return option(ClientOptions.RESPONSE_TIMEOUT_MILLIS, responseTimeoutMillis);
    }

    /**
     * Sets the maximum allowed length of a server response in bytes.
     *
     * @param maxResponseLength the maximum length in bytes. {@code 0} disables the limit.
     */
    public AbstractClientOptionsBuilder maxResponseLength(long maxResponseLength) {
        return option(ClientOptions.MAX_RESPONSE_LENGTH, maxResponseLength);
    }

    /**
     * Sets the {@link Supplier} that generates a {@link RequestId}.
     */
    public AbstractClientOptionsBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return option(ClientOptions.REQUEST_ID_GENERATOR, requestIdGenerator);
    }

    /**
     * Sets the {@link SuccessFunction} to allow custom definition of successful responses.
     * {@link MetricCollectingClient}, {@link LoggingClient} and {@link LoggingRpcClient} will use this custom
     * definition if set.
     * If not set, default one in {@link ClientOptions} is used.
     */
    public AbstractClientOptionsBuilder successFunction(SuccessFunction successFunction) {
        return option(ClientOptions.SUCCESS_FUNCTION, successFunction);
    }

    /**
     * Sets a {@link Function} that remaps an {@link Endpoint} into an {@link EndpointGroup}.
     * This {@link ClientOption} is useful when you need to override a single target host into
     * a group of hosts to enable client-side load-balancing, e.g.
     * <pre>{@code
     * MyService.Iface client =
     *     Clients.newClient("tbinary+http://example.com/api",
     *                       MyService.Iface.class);
     *
     * EndpointGroup myGroup = EndpointGroup.of(Endpoint.of("node-1.example.com")),
     *                                          Endpoint.of("node-2.example.com")));
     *
     * MyService.Iface derivedClient =
     *     Clients.newDerivedClient(client, options -> {
     *         return options.toBuilder()
     *                       .endpointRemapper(endpoint -> {
     *                           if (endpoint.host().equals("example.com")) {
     *                               return myGroup;
     *                           } else {
     *                               return endpoint;
     *                           }
     *                       })
     *                       .build();
     *     });
     *
     * // This request goes to 'node-1.example.com' or 'node-2.example.com'.
     * derivedClient.call();
     * }</pre>
     *
     * <p>Note that the remapping does not occur recursively but only once.</p>
     *
     * @see ClientOptions#ENDPOINT_REMAPPER
     * @see ClientOptions#endpointRemapper()
     */
    public AbstractClientOptionsBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        requireNonNull(endpointRemapper, "endpointRemapper");
        return option(ClientOptions.ENDPOINT_REMAPPER, endpointRemapper);
    }

    /**
     * Adds the specified HTTP-level {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms an {@link HttpClient} to another
     */
    public AbstractClientOptionsBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        decoration.add(decorator);
        return this;
    }

    /**
     * Adds the specified HTTP-level {@code decorator}.
     *
     * @param decorator the {@link DecoratingHttpClientFunction} that intercepts an invocation
     */
    public AbstractClientOptionsBuilder decorator(DecoratingHttpClientFunction decorator) {
        decoration.add(decorator);
        return this;
    }

    /**
     * Clears all HTTP-level and RPC-level decorators set so far.
     */
    public AbstractClientOptionsBuilder clearDecorators() {
        decoration.clear();
        return this;
    }

    /**
     * Adds the specified RPC-level {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms an {@link RpcClient} to another
     */
    public AbstractClientOptionsBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        decoration.addRpc(decorator);
        return this;
    }

    /**
     * Adds the specified RPC-level {@code decorator}.
     *
     * @param decorator the {@link DecoratingRpcClientFunction} that intercepts an invocation
     */
    public AbstractClientOptionsBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        decoration.addRpc(decorator);
        return this;
    }

    /**
     * Adds the specified HTTP header.
     */
    public AbstractClientOptionsBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headers.addObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Adds the specified HTTP headers.
     */
    public AbstractClientOptionsBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        this.headers.addObject(headers);
        return this;
    }

    /**
     * Sets the specified HTTP header.
     */
    public AbstractClientOptionsBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headers.setObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Sets the specified HTTP headers.
     */
    public AbstractClientOptionsBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        this.headers.setObject(headers);
        return this;
    }

    /**
     * Sets the
     * <a href="https://en.wikipedia.org/wiki/Basic_access_authentication">HTTP basic access authentication</a>
     * header using {@link HttpHeaderNames#AUTHORIZATION}.
     *
     * @deprecated Use {@link #auth(AuthToken)} instead.
     */
    @Deprecated
    public AbstractClientOptionsBuilder auth(BasicToken token) {
        return auth((AuthToken) token);
    }

    /**
     * Sets the <a href="https://oauth.net/core/1.0a/">OAuth Core 1.0 Revision A</a> header
     * using {@link HttpHeaderNames#AUTHORIZATION}.
     *
     * @deprecated Use {@link #auth(AuthToken)} instead.
     */
    @Deprecated
    public AbstractClientOptionsBuilder auth(OAuth1aToken token) {
        return auth((AuthToken) token);
    }

    /**
     * Sets the <a href="https://www.oauth.com/">OAuth 2.0</a> header using
     * {@link HttpHeaderNames#AUTHORIZATION}.
     *
     * @deprecated Use {@link #auth(AuthToken)} instead.
     */
    @Deprecated
    public AbstractClientOptionsBuilder auth(OAuth2Token token) {
        return auth((AuthToken) token);
    }

    /**
     * Sets the {@link AuthToken} header using {@link HttpHeaderNames#AUTHORIZATION}.
     */
    public AbstractClientOptionsBuilder auth(AuthToken token) {
        requireNonNull(token, "token");
        headers.set(HttpHeaderNames.AUTHORIZATION, token.asHeaderValue());
        return this;
    }

    /**
     * Enables <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">automatic redirection</a>.
     */
    @UnstableApi
    public AbstractClientOptionsBuilder followRedirects() {
        return option(REDIRECT_CONFIG, RedirectConfig.of());
    }

    /**
     * Sets the {@link RedirectConfig} to enable
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">automatic redirection</a>.
     */
    @UnstableApi
    public AbstractClientOptionsBuilder followRedirects(RedirectConfig redirectConfig) {
        return option(REDIRECT_CONFIG, requireNonNull(redirectConfig, "redirectConfig"));
    }

    /**
     * Builds {@link ClientOptions} with the given options and the
     * {@linkplain ClientOptions#of() default options}.
     */
    protected final ClientOptions buildOptions() {
        return buildOptions(null);
    }

    /**
     * Builds {@link ClientOptions} with the specified {@code baseOptions} and
     * the options which were set to this builder.
     */
    protected final ClientOptions buildOptions(@Nullable ClientOptions baseOptions) {
        final Collection<ClientOptionValue<?>> optVals = options.values();
        final int numOpts = optVals.size();
        final ClientOptionValue<?>[] optValArray = optVals.toArray(new ClientOptionValue[numOpts + 2]);
        optValArray[numOpts] = ClientOptions.DECORATION.newValue(decoration.build());
        optValArray[numOpts + 1] = ClientOptions.HEADERS.newValue(headers.build());

        if (baseOptions != null) {
            return ClientOptions.of(baseOptions, optValArray);
        } else {
            return ClientOptions.of(optValArray);
        }
    }
}
