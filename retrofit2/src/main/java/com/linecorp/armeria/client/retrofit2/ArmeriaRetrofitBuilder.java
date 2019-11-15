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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.GROUP_PREFIX;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.Retrofit.Builder;
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
    private final ClientFactory clientFactory;
    @Nullable
    private String baseUrl;
    private boolean streaming;
    private Executor callbackExecutor = CommonPools.blockingTaskExecutor();
    private BiFunction<String, ? super ClientOptionsBuilder, ClientOptionsBuilder> configurator =
            DEFAULT_CONFIGURATOR;

    /**
     * Creates a {@link ArmeriaRetrofitBuilder} with the default {@link ClientFactory}.
     */
    public ArmeriaRetrofitBuilder() {
        this(ClientFactory.ofDefault());
    }

    /**
     * Creates a {@link ArmeriaRetrofitBuilder} with the specified {@link ClientFactory}.
     */
    public ArmeriaRetrofitBuilder(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        retrofitBuilder = new Retrofit.Builder();
    }

    /**
     * Sets the API base URL.
     *
     * @see Builder#baseUrl(String)
     */
    public ArmeriaRetrofitBuilder baseUrl(String baseUrl) {
        return baseUrl(URI.create(requireNonNull(baseUrl, "baseUrl")));
    }

    /**
     * Sets the API base URL.
     *
     * @see Builder#baseUrl(String)
     */
    public ArmeriaRetrofitBuilder baseUrl(URI baseUrl) {
        requireNonNull(baseUrl, "baseUrl");
        checkArgument(SessionProtocol.find(baseUrl.getScheme()).isPresent(),
                      "baseUrl must have an HTTP scheme: %s", baseUrl);
        final String path = baseUrl.getPath();
        if (!path.isEmpty() && !SLASH.equals(path.substring(path.length() - 1))) {
            throw new IllegalArgumentException("baseUrl must end with /: " + baseUrl);
        }
        this.baseUrl = baseUrl.toString();
        return this;
    }

    /**
     * Sets the {@link ClientOptions} that customizes the underlying {@link WebClient}.
     * This method can be useful if you already have an Armeria client and want to reuse its configuration,
     * such as using the same decorators.
     * <pre>{@code
     * WebClient myClient = ...;
     * // Use the same settings and decorators with `myClient` when sending requests.
     * builder.clientOptions(myClient.options());
     * }</pre>
     */
    public ArmeriaRetrofitBuilder clientOptions(ClientOptions clientOptions) {
        requireNonNull(clientOptions, "clientOptions");
        return withClientOptions((uri, b) -> b.options(clientOptions));
    }

    /**
     * Sets the {@link BiFunction} that customizes the underlying {@link WebClient}.
     * <pre>{@code
     * builder.withClientOptions((uri, b) -> {
     *     if (uri.startsWith("https://foo.com/")) {
     *         return b.setHttpHeader(HttpHeaders.AUTHORIZATION,
     *                                "bearer my-access-token")
     *                 .responseTimeout(Duration.ofSeconds(3));
     *     } else {
     *         return b;
     *     }
     * });
     * }</pre>
     *
     * @param configurator a {@link BiFunction} whose first argument is the the URI of the server endpoint and
     *                     whose second argument is the {@link ClientOptionsBuilder} with default options of
     *                     the new derived client
     */
    public ArmeriaRetrofitBuilder withClientOptions(
            BiFunction<String, ? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        this.configurator = requireNonNull(configurator, "configurator");
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
        checkState(baseUrl != null, "baseUrl not set");
        final URI uri = URI.create(baseUrl);
        final String fullUri = SessionProtocol.of(uri.getScheme()) + "://" + uri.getAuthority();
        final WebClient baseHttpClient = WebClient.of(
                clientFactory, fullUri, configurator.apply(fullUri, ClientOptions.builder()).build());
        return retrofitBuilder.baseUrl(convertToOkHttpUrl(baseHttpClient, uri.getPath(), GROUP_PREFIX))
                              .callFactory(new ArmeriaCallFactory(
                                      baseHttpClient, clientFactory, configurator,
                                      streaming ? SubscriberFactory.streaming(callbackExecutor)
                                                : SubscriberFactory.blocking()))
                              .build();
    }

    private static HttpUrl convertToOkHttpUrl(WebClient baseHttpClient, String basePath,
                                              String groupPrefix) {
        final URI uri = baseHttpClient.uri();
        final SessionProtocol sessionProtocol = Scheme.tryParse(uri.getScheme())
                                                      .map(Scheme::sessionProtocol)
                                                      .orElseGet(() -> SessionProtocol.of(uri.getScheme()));
        final String authority = uri.getAuthority();
        final String protocol = sessionProtocol.isTls() ? "https" : "http";
        final HttpUrl parsed;
        if (authority.startsWith("group:")) {
            parsed = HttpUrl.parse(protocol + "://" + authority.replace("group:", groupPrefix) + basePath);
        } else {
            parsed = HttpUrl.parse(protocol + "://" + authority + basePath);
        }
        assert parsed != null;
        return parsed;
    }
}
