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
package com.linecorp.armeria.client.retrofit2;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.AbstractClientOptionsBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RedirectConfig;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.http.Streaming;

/**
 * A builder that creates a {@link Retrofit} which uses {@link WebClient} for sending requests.
 *
 * @see ArmeriaRetrofit
 */
public final class ArmeriaRetrofitBuilder extends AbstractClientOptionsBuilder {

    private final Retrofit.Builder retrofitBuilder;
    private final String baseWebClientHost;
    private final int baseWebClientPort;
    private final WebClient webClient;
    private boolean streaming;
    private Executor callbackExecutor = CommonPools.blockingTaskExecutor();
    @Nullable
    private BiFunction<? super SessionProtocol, ? super Endpoint, ? extends WebClient> nonBaseClientFactory;

    ArmeriaRetrofitBuilder(WebClient webClient) {
        this.webClient = webClient;
        final URI uri = webClient.uri();
        final SessionProtocol protocol = webClient.scheme().sessionProtocol();

        // Build a baseUrl that will pass Retrofit's validation.
        final HttpUrl baseUrl = HttpUrl.get((protocol.isTls() ? "https" : "http") +
                                            uri.toString().substring(protocol.uriText().length()));

        retrofitBuilder = new Retrofit.Builder().baseUrl(baseUrl);
        baseWebClientHost = baseUrl.host();
        baseWebClientPort = baseUrl.port();
    }

    /**
     * Specifies the {@link BiFunction} that creates a new non-base {@link WebClient}, which is used for
     * sending requests to other authorities than that of base URL. If not specified, the non-base
     * {@link WebClient} will have the same options with the base {@link WebClient}, which was specified
     * with {@link ArmeriaRetrofit#of(WebClient)} or {@link ArmeriaRetrofit#builder(WebClient)}.
     *
     * <p>To avoid the overhead of repetitive instantiation of {@link WebClient}s, the {@link WebClient}s
     * returned by the specified {@link BiFunction} will be cached for each combination of:
     * <ul>
     *   <li>Whether the connection is secured (HTTPS or HTTPS)</li>
     *   <li>Host name</li>
     *   <li>Port number</li>
     * </ul>
     *
     * <p>You can use this method to create a customized non-base {@link WebClient}, for example to send
     * an additional header, override the timeout or enforce HTTP/1:
     * <pre>{@code
     * ArmeriaRetrofit.builder("http://example.com/")
     *                .nonBaseClientFactory((protocol, endpoint) -> {
     *                    // Enforce HTTP/1.
     *                    final SessionProtocol actualProtocol =
     *                            protocol.isTls() ? SessionProtocol.H1 : SessionProtocol.H1C;
     *
     *                    return WebClient.builder(actualProtocol, endpoint)
     *                                    // Derive most settings from 'defaultWebClient'.
     *                                    .factory(defaultWebClient.factory())
     *                                    .options(defaultWebClient.options())
     *                                    // Set a custom header.
     *                                    .setHeader(HttpHeaderNames.AUTHORIZATION,
     *                                               "bearer my-access-token")
     *                                    // Override the timeout.
     *                                    .responseTimeout(Duration.ofSeconds(30))
     *                                    .build();
     *                })
     *                .build();
     * }</pre>
     *
     * <p>Note that the specified {@link BiFunction} is not used for sending requests to the base URL's
     * authority. The default {@link WebClient} specified with {@link ArmeriaRetrofit#of(WebClient)} or
     * {@link ArmeriaRetrofit#builder(WebClient)} will be used instead for such requests:
     * <pre>{@code
     * // No need to use 'nonBaseClientFactory()' method.
     * ArmeriaRetrofit.of(WebClient.builder("http://example.com/")
     *                             .setHeader(HttpHeaderNames.AUTHORIZATION,
     *                                        "bearer my-access-token")
     *                             .build());
     * }</pre>
     */
    public ArmeriaRetrofitBuilder nonBaseClientFactory(
            BiFunction<? super SessionProtocol, ? super Endpoint, ? extends WebClient> nonBaseClientFactory) {
        this.nonBaseClientFactory = requireNonNull(nonBaseClientFactory, "nonBaseClientFactory");
        return this;
    }

    /**
     * Adds the specified converter factory for serialization and deserialization of objects.
     * @see Retrofit.Builder#addCallAdapterFactory(CallAdapter.Factory)
     */
    public ArmeriaRetrofitBuilder addConverterFactory(Converter.Factory factory) {
        retrofitBuilder.addConverterFactory(requireNonNull(factory, "factory"));
        return this;
    }

    /**
     * Adds the specified call adapter factory for supporting service method return types other than {@link
     * Call}.
     * @see Retrofit.Builder#addCallAdapterFactory(CallAdapter.Factory)
     */
    public ArmeriaRetrofitBuilder addCallAdapterFactory(CallAdapter.Factory factory) {
        retrofitBuilder.addCallAdapterFactory(requireNonNull(factory, "factory"));
        return this;
    }

    /**
     * Sets the {@link Executor} on which {@link Callback} methods are invoked when returning {@link Call} from
     * your service method.
     *
     * <p>Note: {@code executor} is not used for {@link #addCallAdapterFactory custom method return types}.
     *
     * @see Retrofit.Builder#callbackExecutor(Executor)
     */
    public ArmeriaRetrofitBuilder callbackExecutor(Executor executor) {
        callbackExecutor = requireNonNull(executor, "executor");
        retrofitBuilder.callbackExecutor(callbackExecutor);
        return this;
    }

    /**
     * Sets the streaming flag to make Armeria client fully support {@link Streaming}.
     * If this flag is {@code false}, Armeria client will buffer all data and call a callback after receiving
     * the data completely, even if a service method was annotated with {@link Streaming}.
     * By enabling this flag, Armeria client will use the {@link Executor} specified with
     * {@link ArmeriaRetrofitBuilder#callbackExecutor(Executor)} to read the data in a blocking way.
     *
     * <p>Note: It is not recommended to have the service methods both with and without the
     * {@link Streaming} annotation in the same interface. Consider separating them into different
     * interfaces and using different builder to build the service.</p>
     *
     * @see Streaming
     */
    public ArmeriaRetrofitBuilder streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    /**
     * When calling {@link Retrofit#create} on the resulting {@link Retrofit} instance, eagerly validate
     * the configuration of all methods in the supplied interface.
     *
     * @see Retrofit.Builder#validateEagerly(boolean)
     */
    public ArmeriaRetrofitBuilder validateEagerly(boolean validateEagerly) {
        retrofitBuilder.validateEagerly(validateEagerly);
        return this;
    }

    /**
     * Returns a newly-created {@link Retrofit} based on the properties of this builder.
     */
    public Retrofit build() {
        final SessionProtocol protocol = webClient.scheme().sessionProtocol();

        final ClientOptions retrofitOptions = buildOptions(webClient.options());
        // Re-create the base client without a path, because Retrofit will always provide a full path.
        final WebClient baseWebClient = WebClient.builder(protocol, webClient.endpointGroup())
                                                 .options(retrofitOptions)
                                                 .build();

        if (nonBaseClientFactory == null) {
            nonBaseClientFactory = (p, url) -> WebClient.builder(p, Endpoint.of(url.host(), url.port()))
                                                        .options(retrofitOptions)
                                                        .build();
        }

        retrofitBuilder.callFactory(new ArmeriaCallFactory(
                baseWebClientHost, baseWebClientPort, baseWebClient,
                streaming ? SubscriberFactory.streaming(callbackExecutor)
                          : SubscriberFactory.blocking(), new CachedNonBaseClientFactory(nonBaseClientFactory)
        ));

        return retrofitBuilder.build();
    }

    private static class CachedNonBaseClientFactory
            implements BiFunction<SessionProtocol, Endpoint, WebClient> {

        private final BiFunction<SessionProtocol, Endpoint, WebClient> nonBaseClientFactory;
        private final Cache<Map.Entry<SessionProtocol, Endpoint>, WebClient> cache;

        @SuppressWarnings("unchecked")
        CachedNonBaseClientFactory(
                BiFunction<? super SessionProtocol, ? super Endpoint,
                        ? extends WebClient> nonBaseClientFactory) {

            this.nonBaseClientFactory = (BiFunction<SessionProtocol, Endpoint, WebClient>) nonBaseClientFactory;

            cache = Caffeine.newBuilder()
                            .maximumSize(8192) // TODO(trustin): Add a flag if there's demand for it.
                            .build();
        }

        @Override
        public WebClient apply(SessionProtocol protocol, Endpoint endpoint) {
            final Map.Entry<SessionProtocol, Endpoint> key = Maps.immutableEntry(protocol, endpoint);
            final WebClient webClient =
                    cache.get(key, unused -> nonBaseClientFactory.apply(protocol, endpoint));
            checkState(webClient != null, "nonBaseClientFactory returned null.");
            return webClient;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("nonBaseClientFactory", nonBaseClientFactory)
                              .add("cache", cache)
                              .toString();
        }
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ArmeriaRetrofitBuilder options(ClientOptions options) {
        return (ArmeriaRetrofitBuilder) super.options(options);
    }

    @Override
    public ArmeriaRetrofitBuilder options(ClientOptionValue<?>... options) {
        return (ArmeriaRetrofitBuilder) super.options(options);
    }

    @Override
    public ArmeriaRetrofitBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (ArmeriaRetrofitBuilder) super.options(options);
    }

    @Override
    public <T> ArmeriaRetrofitBuilder option(ClientOption<T> option, T value) {
        return (ArmeriaRetrofitBuilder) super.option(option, value);
    }

    @Override
    public <T> ArmeriaRetrofitBuilder option(ClientOptionValue<T> optionValue) {
        return (ArmeriaRetrofitBuilder) super.option(optionValue);
    }

    @Override
    public ArmeriaRetrofitBuilder factory(ClientFactory factory) {
        return (ArmeriaRetrofitBuilder) super.factory(factory);
    }

    @Override
    public ArmeriaRetrofitBuilder writeTimeout(Duration writeTimeout) {
        return (ArmeriaRetrofitBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public ArmeriaRetrofitBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (ArmeriaRetrofitBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public ArmeriaRetrofitBuilder responseTimeout(Duration responseTimeout) {
        return (ArmeriaRetrofitBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public ArmeriaRetrofitBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (ArmeriaRetrofitBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public ArmeriaRetrofitBuilder maxResponseLength(long maxResponseLength) {
        return (ArmeriaRetrofitBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public ArmeriaRetrofitBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (ArmeriaRetrofitBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public ArmeriaRetrofitBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (ArmeriaRetrofitBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public ArmeriaRetrofitBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (ArmeriaRetrofitBuilder) super.decorator(decorator);
    }

    @Override
    public ArmeriaRetrofitBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (ArmeriaRetrofitBuilder) super.decorator(decorator);
    }

    @Override
    public ArmeriaRetrofitBuilder clearDecorators() {
        return (ArmeriaRetrofitBuilder) super.clearDecorators();
    }

    /**
     * Raises an {@link UnsupportedOperationException} because this builder doesn't support RPC-level but only
     * HTTP-level decorators.
     */
    @Override
    public ArmeriaRetrofitBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (ArmeriaRetrofitBuilder) super.rpcDecorator(decorator);
    }

    /**
     * Raises an {@link UnsupportedOperationException} because this builder doesn't support RPC-level but only
     * HTTP-level decorators.
     */
    @Override
    public ArmeriaRetrofitBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (ArmeriaRetrofitBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ArmeriaRetrofitBuilder addHeader(CharSequence name, Object value) {
        return (ArmeriaRetrofitBuilder) super.addHeader(name, value);
    }

    @Override
    public ArmeriaRetrofitBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ArmeriaRetrofitBuilder) super.addHeaders(headers);
    }

    @Override
    public ArmeriaRetrofitBuilder setHeader(CharSequence name, Object value) {
        return (ArmeriaRetrofitBuilder) super.setHeader(name, value);
    }

    @Override
    public ArmeriaRetrofitBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ArmeriaRetrofitBuilder) super.setHeaders(headers);
    }

    @Override
    public ArmeriaRetrofitBuilder auth(BasicToken token) {
        return (ArmeriaRetrofitBuilder) super.auth(token);
    }

    @Override
    public ArmeriaRetrofitBuilder auth(OAuth1aToken token) {
        return (ArmeriaRetrofitBuilder) super.auth(token);
    }

    @Override
    public ArmeriaRetrofitBuilder auth(OAuth2Token token) {
        return (ArmeriaRetrofitBuilder) super.auth(token);
    }

    @Override
    public ArmeriaRetrofitBuilder followRedirects() {
        return (ArmeriaRetrofitBuilder) super.followRedirects();
    }

    @Override
    public ArmeriaRetrofitBuilder followRedirects(RedirectConfig redirectConfig) {
        return (ArmeriaRetrofitBuilder) super.followRedirects(redirectConfig);
    }
}
