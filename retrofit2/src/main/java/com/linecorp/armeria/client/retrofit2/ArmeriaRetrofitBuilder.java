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
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import okhttp3.HttpUrl;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.http.Streaming;

/**
 * A helper class for creating a new {@link Retrofit} instance with {@link ArmeriaCallFactory}.
 * For example,
 *
 * <pre>{@code
 * Retrofit retrofit = new ArmeriaRetrofitBuilder()
 *     .baseUrl("http://localhost:8080/")
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response<User> user = api.getUser().execute();
 * }</pre>
 *
 * <p>{@link ArmeriaRetrofitBuilder} even supports {@link EndpointGroup}, so you can create {@link Retrofit}
 * like below,
 *
 * <pre>{@code
 * EndpointGroupRegistry.register("foo",
 *                                new StaticEndpointGroup(Endpoint.of("127.0.0.1", 8080)),
 *                                ROUND_ROBIN);
 *
 * Retrofit retrofit = new ArmeriaRetrofitBuilder()
 *     .baseUrl("http://group:foo/")
 *     .build();
 * }</pre>
 */
public final class ArmeriaRetrofitBuilder {

    private static final BiFunction<String, ? super ClientOptionsBuilder, ClientOptionsBuilder>
            DEFAULT_CONFIGURATOR = (url, optionsBuilder) -> optionsBuilder;
    private static final String SLASH = "/";

    private final Retrofit.Builder retrofitBuilder;
    private final ImmutableMap.Builder<String, EndpointGroup> endpointGroups = ImmutableMap.builder();

    private boolean streaming;
    private Executor callbackExecutor = CommonPools.blockingTaskExecutor();

    private Function<? super HttpUrl, ? extends EndpointGroup> endpointGroupMapping =
            url -> Endpoint.of(url.host(), url.port());

    // FIXME(trustin): Do some caching.
    private BiFunction<? super EndpointGroup, ? super HttpUrl, ? extends WebClient> webClientMapping =
            (endpointGroup, url) -> WebClient.of(url.isHttps() ? SessionProtocol.HTTPS : SessionProtocol.HTTP,
                                                 endpointGroup);

    ArmeriaRetrofitBuilder(HttpUrl baseUrl) {
        retrofitBuilder = new Retrofit.Builder().baseUrl(baseUrl);
    }

    public ArmeriaRetrofitBuilder endpointGroup(
            Function<? super HttpUrl, ? extends EndpointGroup> endpointGroupMapping) {
        this.endpointGroupMapping = requireNonNull(endpointGroupMapping, "endpointGroupMapping");
        return this;
    }

    public ArmeriaRetrofitBuilder webClient(
            BiFunction<SessionProtocol, ? super EndpointGroup, ? extends WebClient> webClientFactory) {
        requireNonNull(webClientFactory, "webClientFactory");
        return webClient((endpointGroup, url) -> {
                             final SessionProtocol protocol = url.isHttps() ? SessionProtocol.HTTPS
                                                                            : SessionProtocol.HTTP;
                             return new Key(protocol, url.host(), url.port());
                         },
                         (endpointGroup, key) -> webClientFactory.apply(key.protocol, endpointGroup));
    }

    public <T> ArmeriaRetrofitBuilder webClient(
            BiFunction<? super EndpointGroup, ? super HttpUrl, T> keyFunction,
            BiFunction<? super EndpointGroup, T, ? extends WebClient> webClientFactory) {
        requireNonNull(keyFunction, "keyFunction");
        requireNonNull(webClientFactory, "webClientFactory");

        webClientMapping = new CachedWebClientMapping(keyFunction, webClientFactory);
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
        return retrofitBuilder.callFactory(new ArmeriaCallFactory(
                                      endpointGroupMapping, webClientMapping,
                                      streaming ? SubscriberFactory.streaming(callbackExecutor)
                                                : SubscriberFactory.blocking()))
                              .build();
    }

    private static HttpUrl convertToOkHttpUrl(WebClient baseWebClient) {
        assert baseWebClient.scheme().serializationFormat() == SerializationFormat.NONE;
        final SessionProtocol protocol = baseWebClient.scheme().sessionProtocol();
        final URI uri = baseWebClient.uri();

        final HttpUrl parsed;
        if (protocol == SessionProtocol.HTTP || protocol == SessionProtocol.HTTPS) {
            parsed = HttpUrl.get(uri.toString());
        } else {
            parsed = HttpUrl.get((protocol.isTls() ? "https" : "http") +
                                 uri.toString().substring(protocol.uriText().length()));
        }

        return parsed;
    }

    private static final class Key {
        final SessionProtocol protocol;
        final String host;
        final int port;
        final int hashCode;

        Key(SessionProtocol protocol, String host, int port) {
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            hashCode = (protocol.hashCode() * 31 + host.hashCode()) * 31 + port;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj == null || obj.getClass() != Key.class) {
                return false;
            }

            final Key that = (Key) obj;
            return protocol == that.protocol && port == that.port && host.equals(that.host);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("protocol", protocol)
                              .add("host", host)
                              .add("port", port)
                              .toString();
        }
    }

    private static class CachedWebClientMapping implements BiFunction<EndpointGroup, HttpUrl, WebClient> {

        private final BiFunction<EndpointGroup, HttpUrl, Object> keyFunction;
        private final BiFunction<EndpointGroup, Object, WebClient> webClientFactory;
        private final Cache<Object, WebClient> cache;

        @SuppressWarnings("unchecked")
        <T> CachedWebClientMapping(
                BiFunction<? super EndpointGroup, ? super HttpUrl, T> keyFunction,
                BiFunction<? super EndpointGroup, T, ? extends WebClient> webClientFactory) {
            this.keyFunction = (BiFunction<EndpointGroup, HttpUrl, Object>) keyFunction;
            this.webClientFactory = (BiFunction<EndpointGroup, Object, WebClient>) webClientFactory;

            cache = Caffeine.newBuilder()
                            .maximumSize(8192) // TODO(trustin): Add a flag if there's demand for it.
                            .build();
        }

        @Override
        public WebClient apply(EndpointGroup endpointGroup, HttpUrl httpUrl) {
            final Object key = keyFunction.apply(endpointGroup, httpUrl);
            checkState(key != null, "keyFunction returned null.");
            final WebClient webClient = cache.get(key, key2 -> webClientFactory.apply(endpointGroup, key2));
            checkState(webClient != null, "webClientFactory returned null.");
            return webClient;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("keyFunction", keyFunction)
                              .add("webClientFactory", webClientFactory)
                              .add("cache", cache)
                              .toString();
        }
    }
}
