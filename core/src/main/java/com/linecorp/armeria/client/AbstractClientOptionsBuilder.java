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
import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;
import static com.linecorp.armeria.internal.common.RequestContextUtil.mergeHooks;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
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

    @Nullable
    private Consumer<ClientRequestContext> contextCustomizer;
    private Supplier<AutoCloseable> contextHook = NOOP_CONTEXT_HOOK;

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
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to send additional data even after the response is complete.
     * Specify {@link Duration#ZERO} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    public AbstractClientOptionsBuilder requestAutoAbortDelay(Duration delay) {
        return requestAutoAbortDelayMillis(requireNonNull(delay, "delay").toMillis());
    }

    /**
     * Sets the amount of time in millis to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to send additional data even after the response is complete.
     * Specify {@code 0} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    public AbstractClientOptionsBuilder requestAutoAbortDelayMillis(long delayMillis) {
        option(ClientOptions.REQUEST_AUTO_ABORT_DELAY_MILLIS, delayMillis);
        return this;
    }

    /**
     * Sets the {@link Supplier} that generates a {@link RequestId}.
     */
    public AbstractClientOptionsBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return option(ClientOptions.REQUEST_ID_GENERATOR, requestIdGenerator);
    }

    /**
     * Sets a {@link SuccessFunction} that determines whether a request was handled successfully or not.
     * If unspecified, {@link SuccessFunction#ofDefault()} is used.
     */
    @UnstableApi
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
     * Sets the {@link Supplier} which provides an {@link AutoCloseable} and will be called whenever this
     * {@link RequestContext} is popped from the {@link RequestContextStorage}.
     *
     * @param contextHook the {@link Supplier} that provides an {@link AutoCloseable}
     */
    @UnstableApi
    public AbstractClientOptionsBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        requireNonNull(contextHook, "contextHook");
        this.contextHook = mergeHooks(this.contextHook, contextHook);
        return this;
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
     * Adds the default HTTP header for an {@link HttpRequest} that will be sent by this {@link Client}.
     *
     * <p>Note that the values of the default HTTP headers could be overridden if the same
     * {@link HttpHeaderNames} are defined in the {@link HttpRequest#headers()} or
     * {@link ClientRequestContext#additionalRequestHeaders()}.
     */
    public AbstractClientOptionsBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headers.addObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Adds the default HTTP headers for an {@link HttpRequest} that will be sent by this {@link Client}.
     *
     * <p>Note that the values of the default HTTP headers could be overridden if the same
     * {@link HttpHeaderNames} are defined in the {@link HttpRequest#headers()} or
     * {@link ClientRequestContext#additionalRequestHeaders()}.
     */
    public AbstractClientOptionsBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        this.headers.addObject(headers);
        return this;
    }

    /**
     * Sets the default HTTP header for an {@link HttpRequest} that will be sent by this {@link Client}.
     *
     * <p>Note that the default HTTP header could be overridden if the same {@link HttpHeaderNames} are
     * defined in {@link HttpRequest#headers()} or {@link ClientRequestContext#additionalRequestHeaders()}.
     */
    public AbstractClientOptionsBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headers.setObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Sets the default HTTP headers for an {@link HttpRequest} that will be sent by this {@link Client}.
     *
     * <p>Note that the values of the default HTTP headers could be overridden if the same
     * {@link HttpHeaderNames} are defined in {@link HttpRequest#headers()} or
     * {@link ClientRequestContext#additionalRequestHeaders()}.
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
     * Adds the specified {@link ClientRequestContext} customizer function so that
     * it customizes the context in the thread that initiated the client call.
     * The given customizer function is evaluated before the customizer function specified by
     * {@link Clients#withContextCustomizer(Consumer)}.
     *
     * <pre>{@code
     * static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
     * static final AttributeKey<String> USER_ID_ATTR = AttributeKey.valueOf("USER_ID");
     * ...
     * MyClientStub client =
     *     Clients.builder(...)
     *            .contextCustomizer(ctx -> {
     *                // This customizer will be invoked from the thread that initiates a client call.
     *                ctx.setAttr(USER_ID_ATTR, USER_ID.get());
     *            })
     *            .build(MyClientStub.class);
     *
     * // Good:
     * // The context data is set by the thread that initiates the client call.
     * USER_ID.set("user1");
     * try {
     *     client.executeSomething1(..);
     *     ...
     *     client.executeSomethingN(..);
     * } finally {
     *     // Should clean up the thread local storage.
     *     USER_ID.remove();
     * }
     *
     * // Bad:
     * USER_ID.set("user1");
     * executor.execute(() -> {
     *     // The variable in USER_ID won't be propagated to the context.
     *     // The variable is not valid at the moment client.executeSomething1() is called.
     *      client.executeSomething1(..);
     * });
     * }</pre>
     *
     * <p>Note that certain properties of {@link ClientRequestContext}, such as:
     * <ul>
     *   <li>{@link ClientRequestContext#endpoint()}</li>
     *   <li>{@link ClientRequestContext#localAddress()}</li>
     *   <li>{@link ClientRequestContext#remoteAddress()}</li>
     * </ul>
     * may be {@code null} while the customizer function runs, because the target host of the {@link Request}
     * is not determined yet.
     *
     * @see Clients#withContextCustomizer(Consumer)
     */
    @UnstableApi
    public AbstractClientOptionsBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        requireNonNull(contextCustomizer, "contextCustomizer");
        if (this.contextCustomizer == null) {
            //noinspection unchecked
            this.contextCustomizer = (Consumer<ClientRequestContext>) contextCustomizer;
        } else {
            this.contextCustomizer = this.contextCustomizer.andThen(contextCustomizer);
        }
        return this;
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
        final int extra = contextCustomizer == null ? 3 : 4;
        final ClientOptionValue<?>[] optValArray = optVals.toArray(new ClientOptionValue[numOpts + extra]);
        optValArray[numOpts] = ClientOptions.DECORATION.newValue(decoration.build());
        optValArray[numOpts + 1] = ClientOptions.HEADERS.newValue(headers.build());
        optValArray[numOpts + 2] = ClientOptions.CONTEXT_HOOK.newValue(contextHook);
        if (contextCustomizer != null) {
            optValArray[numOpts + 3] = ClientOptions.CONTEXT_CUSTOMIZER.newValue(contextCustomizer);
        }

        if (baseOptions != null) {
            return ClientOptions.of(baseOptions, optValArray);
        } else {
            return ClientOptions.of(optValArray);
        }
    }
}
