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

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
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
    private final String baseWebClientHost;
    private final int baseWebClientPort;
    private final WebClient baseWebClient;

    private boolean streaming;
    private Executor callbackExecutor = CommonPools.blockingTaskExecutor();
    private BiFunction<? super SessionProtocol, ? super HttpUrl, ? extends WebClient> nonBaseClientFactory;

    ArmeriaRetrofitBuilder(WebClient webClient) {
        final URI uri = webClient.uri();
        final SessionProtocol protocol = webClient.scheme().sessionProtocol();

        // Build a baseUrl that will pass Retrofit's validation.
        final HttpUrl baseUrl = HttpUrl.get((protocol.isTls() ? "https" : "http") +
                                            uri.toString().substring(protocol.uriText().length()));

        retrofitBuilder = new Retrofit.Builder().baseUrl(baseUrl);
        baseWebClientHost = baseUrl.host();
        baseWebClientPort = baseUrl.port();

        // Re-create the base client without a path, because Retrofit will always provide a full path.
        baseWebClient = WebClient.builder(protocol,
                                          webClient.endpointGroup())
                                 .factory(webClient.factory())
                                 .options(webClient.options())
                                 .build();

        nonBaseClientFactory = (p, url) -> WebClient.builder(p, Endpoint.of(url.host(), url.port()))
                                                    .factory(baseWebClient.factory())
                                                    .options(baseWebClient.options())
                                                    .build();
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
     * </ul></p>
     *
     * <p>You can use this method to create a customized non-base {@link WebClient}, for example to send
     * an additional header, override the timeout, enforce HTTP/1 or even override the target host:
     * <pre>{@code
     * EndpointGroup groupFoo = EndpointGroup.of(Endpoint.of("node-1.foo.com"),
     *                                           Endpoint.of("node-2.foo.com"));
     * EndpointGroup groupBar = EndpointGroup.of(Endpoint.of("node-1.bar.com"),
     *                                           Endpoint.of("node-2.bar.com"));
     *
     * WebClient defaultWebClient = WebClient.of(SessionProtocol.HTTP, groupFoo);
     *
     * ArmeriaRetrofit.builder(defaultWebClient)
     *                .nonBaseClientFactory((protocol, url) -> {
     *                    // Enforce HTTP/1.
     *                    final SessionProtocol actualProtocol =
     *                            protocol.isTls() ? SessionProtocol.H1 : SessionProtocol.H1C;
     *
     *                    final EndpointGroup actualEndpointGroup;
     *                    if ("group-bar".equals(url.host())) {
     *                        // Client-side load-balancing:
     *                        // - Make the request go to 'node-1.bar.com' or 'node-2.bar.com'
     *                        //   if the target host is 'group-bar'.
     *                        actualEndpointGroup = groupBar;
     *                    } else {
     *                        // Use the given host and port otherwise.
     *                        actualEndpointGroup = Endpoint.of(url.host(), url.port());
     *                    }
     *
     *                    return WebClient.builder(actualProtocol, actualEndpointGroup)
     *                                    // Derive most settings from 'defaultWebClient'.
     *                                    .factory(defaultWebClient.factory())
     *                                    .options(defaultWebClient.options())
     *                                    // Set a custom header.
     *                                    .setHttpHeader(HttpHeaderNames.AUTHORIZATION,
     *                                                   "bearer my-access-token")
     *                                    // Override the timeout.
     *                                    .responseTimeout(Duration.ofSeconds(30))
     *                                    .build();
     *                })
     *                .build();
     * }</pre></p>
     *
     * <p>Note that the specified {@link BiFunction} is not used for sending requests to the base URL's
     * authority. The default {@link WebClient} specified with {@link ArmeriaRetrofit#of(WebClient)} or
     * {@link ArmeriaRetrofit#builder(WebClient)} will be used instead for such requests:
     * <pre>{@code
     * // No need to use 'nonBaseClientFactory()' method.
     * ArmeriaRetrofit.of(WebClient.builder("http://example.com/")
     *                             .setHttpHeader(HttpHeaderNames.AUTHORIZATION,
     *                                            "bearer my-access-token")
     *                             .build());
     * }</pre></p>
     */
    public ArmeriaRetrofitBuilder nonBaseClientFactory(
            BiFunction<? super SessionProtocol, ? super HttpUrl, ? extends WebClient> nonBaseClientFactory) {
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
        retrofitBuilder.callFactory(new ArmeriaCallFactory(
                baseWebClientHost, baseWebClientPort, baseWebClient,
                streaming ? SubscriberFactory.streaming(callbackExecutor)
                          : SubscriberFactory.blocking(), new CachedWebClientMapping(nonBaseClientFactory)
        ));

        return retrofitBuilder.build();
    }

    private static class CachedWebClientMapping implements BiFunction<SessionProtocol, HttpUrl, WebClient> {

        private final BiFunction<SessionProtocol, HttpUrl, WebClient> webClientFactory;
        private final Cache<Object, WebClient> cache;

        @SuppressWarnings("unchecked")
        CachedWebClientMapping(
                BiFunction<? super SessionProtocol, ? super HttpUrl, ? extends WebClient> webClientFactory) {

            this.webClientFactory = (BiFunction<SessionProtocol, HttpUrl, WebClient>) webClientFactory;

            cache = Caffeine.newBuilder()
                            .maximumSize(8192) // TODO(trustin): Add a flag if there's demand for it.
                            .build();
        }

        @Override
        public WebClient apply(SessionProtocol protocol, HttpUrl url) {
            final Key key = new Key(protocol, url.host(), url.port());
            final WebClient webClient = cache.get(key, unused -> webClientFactory.apply(protocol, url));
            checkState(webClient != null, "webClientFactory returned null.");
            return webClient;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("webClientFactory", webClientFactory)
                              .add("cache", cache)
                              .toString();
        }
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
}
