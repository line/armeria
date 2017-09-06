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

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.Retrofit.Builder;

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
    private String baseUrl;
    private BiFunction<String, ? super ClientOptionsBuilder, ClientOptionsBuilder> configurator =
            DEFAULT_CONFIGURATOR;

    /**
     * Creates a {@link ArmeriaRetrofitBuilder} with the default {@link ClientFactory}.
     */
    public ArmeriaRetrofitBuilder() {
        this(ClientFactory.DEFAULT);
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
        requireNonNull(baseUrl, "baseUrl");
        URI uri = URI.create(baseUrl);
        checkArgument(SessionProtocol.find(uri.getScheme()).isPresent(),
                      "baseUrl must have an HTTP scheme: %s", baseUrl);
        String path = uri.getPath();
        if (!path.isEmpty() && !SLASH.equals(path.substring(path.length() - 1))) {
            throw new IllegalArgumentException("baseUrl must end with /: " + baseUrl);
        }
        this.baseUrl = baseUrl;
        return this;
    }

    /**
     * Sets the {@link BiFunction} that is applied to the underlying {@link HttpClient}.
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
        retrofitBuilder.callbackExecutor(requireNonNull(executor, "executor"));
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
        final HttpClient baseHttpClient = HttpClient.of(
                clientFactory, fullUri, configurator.apply(fullUri, new ClientOptionsBuilder()).build());
        return retrofitBuilder.baseUrl(convertToOkHttpUrl(baseHttpClient, uri.getPath(), GROUP_PREFIX))
                              .callFactory(new ArmeriaCallFactory(baseHttpClient, clientFactory, configurator))
                              .build();
    }

    private static HttpUrl convertToOkHttpUrl(HttpClient baseHttpClient, String basePath, String groupPrefix) {
        final URI uri = baseHttpClient.uri();
        final SessionProtocol sessionProtocol = Scheme.tryParse(uri.getScheme())
                                                      .map(Scheme::sessionProtocol)
                                                      .orElseGet(() -> SessionProtocol.of(uri.getScheme()));
        final String authority = uri.getAuthority();
        String protocol = sessionProtocol.isTls() ? "https" : "http";
        if (authority.startsWith("group:")) {
            return HttpUrl.parse(protocol + "://" + authority.replace("group:", groupPrefix) + basePath);
        } else {
            return HttpUrl.parse(protocol + "://" + authority + basePath);
        }
    }
}
