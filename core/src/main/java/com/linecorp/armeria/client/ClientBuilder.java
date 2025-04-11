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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override. If you are creating an
 * {@link WebClient}, it is recommended to use the {@link WebClientBuilder} or
 * factory methods in {@link WebClient}.
 *
 * <h2>How are decorators and HTTP headers configured?</h2>
 *
 * <p>Unlike other options, when a user calls {@link #option(ClientOption, Object)} or {@code options()} with
 * a {@link ClientOptions#DECORATION} or a {@link ClientOptions#HEADERS}, this builder will not simply
 * replace the old option but <em>merge</em> the specified option into the previous option value. For example:
 * <pre>{@code
 * ClientOptionsBuilder b = ClientOptions.builder();
 * b.option(ClientOption.HEADERS, headersA);
 * b.option(ClientOption.HEADERS, headersB);
 * b.option(ClientOption.DECORATION, decorationA);
 * b.option(ClientOption.DECORATION, decorationB);
 *
 * ClientOptions opts = b.build();
 * HttpHeaders headers = opts.headers();
 * ClientDecoration decorations = opts.decoration();
 * }</pre>
 * {@code headers} will contain all HTTP headers of {@code headersA} and {@code headersB}.
 * If {@code headersA} and {@code headersB} have the headers with the same name, the duplicate header in
 * {@code headerB} will replace the one with the same name in {@code headerA}.
 * Similarly, {@code decorations} will contain all decorators of {@code decorationA} and {@code decorationB},
 * but there will be no replacement but only addition.
 */
public final class ClientBuilder extends AbstractClientOptionsBuilder {

    @Nullable
    private final URI uri;
    @Nullable
    private final EndpointGroup endpointGroup;
    @Nullable
    private final String path;
    private final Scheme scheme;

    ClientBuilder(URI uri) {
        checkArgument(uri.getScheme() != null, "uri must have scheme: %s", uri);
        checkArgument(uri.getRawAuthority() != null, "uri must have authority: %s", uri);
        this.uri = uri;
        endpointGroup = null;
        path = null;
        scheme = Scheme.parse(uri.getScheme());
    }

    ClientBuilder(Scheme scheme, EndpointGroup endpointGroup, @Nullable String path) {
        if (path != null) {
            checkArgument(path.startsWith("/"),
                          "path: %s (expected: an absolute path starting with '/')", path);
        }
        uri = null;
        this.endpointGroup = endpointGroup;
        this.path = path;
        this.scheme = scheme;
    }

    /**
     * Returns a newly-created client which implements the specified {@code clientType}, based on the
     * properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link Clients#builder(String)} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     */
    public <T> T build(Class<T> clientType) {
        requireNonNull(clientType, "clientType");

        final Object client;
        final ClientOptions options = buildOptions();
        final ClientFactory factory = options.factory();
        if (uri != null) {
            client = factory.newClient(ClientBuilderParams.of(uri, clientType, options));
        } else {
            assert endpointGroup != null;
            client = factory.newClient(ClientBuilderParams.of(scheme, endpointGroup,
                                                              path, clientType, options));
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) client;
        return cast;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ClientBuilder options(ClientOptions options) {
        return (ClientBuilder) super.options(options);
    }

    @Override
    public ClientBuilder options(ClientOptionValue<?>... options) {
        return (ClientBuilder) super.options(options);
    }

    @Override
    public ClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (ClientBuilder) super.options(options);
    }

    @Override
    public <T> ClientBuilder option(ClientOption<T> option, T value) {
        return (ClientBuilder) super.option(option, value);
    }

    @Override
    public <T> ClientBuilder option(ClientOptionValue<T> optionValue) {
        return (ClientBuilder) super.option(optionValue);
    }

    @Override
    public ClientBuilder factory(ClientFactory factory) {
        return (ClientBuilder) super.factory(factory);
    }

    @Override
    public ClientBuilder writeTimeout(Duration writeTimeout) {
        return (ClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public ClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (ClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public ClientBuilder responseTimeout(Duration responseTimeout) {
        return (ClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public ClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (ClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public ClientBuilder maxResponseLength(long maxResponseLength) {
        return (ClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public ClientBuilder requestAutoAbortDelay(Duration delay) {
        return (ClientBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public ClientBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (ClientBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public ClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (ClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    @UnstableApi
    public ClientBuilder successFunction(SuccessFunction successFunction) {
        return (ClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public ClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (ClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public ClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (ClientBuilder) super.decorator(decorator);
    }

    @Override
    public ClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (ClientBuilder) super.decorator(decorator);
    }

    @Override
    public ClientBuilder clearDecorators() {
        return (ClientBuilder) super.clearDecorators();
    }

    @Override
    public ClientBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (ClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (ClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ClientBuilder addHeader(CharSequence name, Object value) {
        return (ClientBuilder) super.addHeader(name, value);
    }

    @Override
    public ClientBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ClientBuilder) super.addHeaders(headers);
    }

    @Override
    public ClientBuilder setHeader(CharSequence name, Object value) {
        return (ClientBuilder) super.setHeader(name, value);
    }

    @Override
    public ClientBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ClientBuilder) super.setHeaders(headers);
    }

    @Override
    public ClientBuilder auth(BasicToken token) {
        return (ClientBuilder) super.auth(token);
    }

    @Override
    public ClientBuilder auth(OAuth1aToken token) {
        return (ClientBuilder) super.auth(token);
    }

    @Override
    public ClientBuilder auth(OAuth2Token token) {
        return (ClientBuilder) super.auth(token);
    }

    @Override
    public ClientBuilder auth(AuthToken token) {
        return (ClientBuilder) super.auth(token);
    }

    @Override
    public ClientBuilder followRedirects() {
        return (ClientBuilder) super.followRedirects();
    }

    @Override
    public ClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (ClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public ClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (ClientBuilder) super.contextCustomizer(contextCustomizer);
    }

    @Override
    public ClientBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        return (ClientBuilder) super.contextHook(contextHook);
    }

    @Override
    public ClientBuilder responseTimeoutMode(ResponseTimeoutMode responseTimeoutMode) {
        return (ClientBuilder) super.responseTimeoutMode(responseTimeoutMode);
    }

    @Override
    public ClientBuilder preprocessor(HttpPreprocessor decorator) {
        return (ClientBuilder) super.preprocessor(decorator);
    }

    @Override
    public ClientBuilder rpcPreprocessor(RpcPreprocessor decorator) {
        return (ClientBuilder) super.rpcPreprocessor(decorator);
    }
}
