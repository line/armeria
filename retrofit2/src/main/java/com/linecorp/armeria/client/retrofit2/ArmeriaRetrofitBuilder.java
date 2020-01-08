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

import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.SessionProtocol;

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
public final class ArmeriaRetrofitBuilder {

    private final Retrofit.Builder retrofitBuilder;
    private final ImmutableMap.Builder<String, EndpointGroup> endpointGroups = ImmutableMap.builder();

    private boolean streaming;
    private Executor callbackExecutor = CommonPools.blockingTaskExecutor();

    private Function<? super HttpUrl, ? extends EndpointGroup> endpointGroupMapping =
            url -> Endpoint.of(url.host(), url.port());

    private BiFunction<? super EndpointGroup, ? super HttpUrl, ? extends WebClient> webClientMapping =
            (endpointGroup, url) -> WebClient.of(url.isHttps() ? SessionProtocol.HTTPS : SessionProtocol.HTTP,
                                                 endpointGroup);

    ArmeriaRetrofitBuilder(HttpUrl baseUrl) {
        retrofitBuilder = new Retrofit.Builder().baseUrl(baseUrl);
    }

    /**
     * Specifies the {@link Function} that determines the {@link EndpointGroup} to send a request to for
     * a given {@link HttpUrl}. By default, an {@link HttpUrl} is mapped to the host specified in
     * the {@link HttpUrl}. You can override the default mapping to specify an alternative
     * {@link EndpointGroup}, for example to implement client-side load-balancing:
     *
     * <pre>{@code
     * EndpointGroup myGroup = EndpointGroup.of(Endpoint.of("node-1.myservice.com"),
     *                                          Endpoint.of("node-2.myservice.com"));
     * ArmeriaRetrofit.builder("http://my-group/")
     *                .endpointGroup(url -> {
     *                    if ("my-group".equals(url.host())) {
     *                        return myGroup;
     *                    } else {
     *                        return Endpoint.of(url.host(), url.port());
     *                    }
     *                })
     *                .build();
     * }</pre>
     *
     * @param endpointGroupMapping the {@link Function} that maps an {@link HttpUrl} to an {@link EndpointGroup}
     */
    public ArmeriaRetrofitBuilder endpointGroup(
            Function<? super HttpUrl, ? extends EndpointGroup> endpointGroupMapping) {
        this.endpointGroupMapping = requireNonNull(endpointGroupMapping, "endpointGroupMapping");
        return this;
    }

    /**
     * Specifies the {@link BiFunction} that creates a new {@link WebClient} that sends requests to
     * the specified {@link EndpointGroup} via the specified {@link SessionProtocol}. The {@link WebClient}
     * returned by the {@link BiFunction} will be cached for each combination of:
     * <ul>
     *   <li>Whether the connection is secured (HTTPS or HTTPS)</li>
     *   <li>Host name</li>
     *   <li>Port number</li>
     * </ul>
     *
     * <p>You can use this method to create a non-default {@link WebClient}, for example to send
     * an additional header, set timeout or even enforce HTTP/1 or 2:
     * <pre>{@code
     * ArmeriaRetrofit.builder("http://example.com/")
     *                .webClient((protocol, endpointGroup) -> {
     *                    // Enforce HTTP/1.
     *                    final SessionProtocol actualProtocol =
     *                            protocol.isTls() ? SessionProtocol.H1 : SessionProtocol.H1C;
     *
     *                    return WebClient.builder(actualProtocol, endpointGroup)
     *                                    // Override the timeout.
     *                                    .responseTimeout(Duration.ofSeconds(30))
     *                                    // Add a decorator.
     *                                    .decorator(myDecorator)
     *                                    .build();
     *                })
     *                .build();
     * }</pre></p>
     *
     * @see #webClient(BiFunction, BiFunction)
     */
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

    /**
     * Specifies the two {@link BiFunction}s that are used for creating and caching a new {@link WebClient}
     * that sends requests to the given {@link EndpointGroup}. The {@code keyFunction} must return a cache key,
     * which is used for caching a {@link WebClient}. The {@code webClientFunction} must create a new
     * {@link WebClient} from the given cache key and {@link EndpointGroup}.
     *
     * <p>You can use this method to create a non-default {@link WebClient}, for example to send
     * an additional header, set timeout or even enforce HTTP/1 or 2, for certain requests:
     * <pre>{@code
     * ArmeriaRetrofit.builder("http://example.com/")
     *                .webClient((endpointGroup, url) -> {
     *                    // Use a different cache key for the URLs whose path starts with /restricted/.
     *                    String cacheKey;
     *                    if (url.encodedPath().startsWith("/h1/")) {
     *                        cacheKey = url.scheme() + "://" + url.host() + ':' + url.port + "/restricted/";
     *                    } else {
     *                        cacheKey = url.scheme() + "://" + url.host() + ':' + url.port;
     *                    }
     *                    return cacheKey;
     *                }, (endpointGroup, cacheKey) -> {
     *                    SessionProtocol protocol;
     *                    if (cacheKey.startsWith("https://")) {
     *                        protocol = SessionProtocol.HTTPS;
     *                    } else {
     *                        protocol = SessionProtocol.HTTP;
     *                    }
     *
     *                    // Specify an access token for accessing restricted area.
     *                    if (cacheKey.endsWith("/restricted/") {
     *                        return WebClient.builder(protocol, endpointGroup)
     *                                        .setHttpHeader(HttpHeaderNames.AUTHORIZATION,
     *                                                       "bearer my-access-token")
     *                                        .build();
     *                    } else {
     *                        return WebClient.of(protocol, endpointGroup);
     *                    }
     *                })
     *                .build();
     * }</pre></p>
     *
     * @see #webClient(BiFunction)
     */
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
