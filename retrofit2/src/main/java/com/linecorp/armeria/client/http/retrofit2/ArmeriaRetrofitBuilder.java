/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.http.retrofit2;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

import okhttp3.HttpUrl;
import retrofit2.CallAdapter;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.Retrofit.Builder;

/**
 * A helper class for creating a new {@link Retrofit} instance with {@link ArmeriaCallFactory}.
 * For example,
 * <pre>{@code
 *
 * Retrofit retrofit = new ArmeriaRetrofitBuilder("none+http://localhost:8080/")
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response&lt;User&gt; user = api.getUser().execute();
 * }
 * </pre>
 *
 * <p>ArmeriaRetrofitBuilder even supports EndpointGroup, so you can create Retrofit like below,
 * <pre>{@code
 *
 * EndpointGroupRegistry.register("foo",
 *                                new StaticEndpointGroup(Endpoint.of("127.0.0.1", 8080)),
 *                                ROUND_ROBIN);
 *
 * Retrofit retrofit = new ArmeriaRetrofitBuilder("none+http://group:foo/")
 *     .build();
 * }
 * </pre>
 * ArmeriaRetrofitBuilder will convert http://group:foo to http://__group__foo internally to avoid okHttp3's
 * {@link HttpUrl} throwing exception when parsing http://group:foo . To avoid your client have possibility to
 * access domain which starts with __group__, you can use {@link ArmeriaRetrofitBuilder#groupPrefix(String)} to
 * customize the internal behavior.
 *
 * <p>If you want to decorate HttpClient, using {@link ArmeriaRetrofitBuilder#newClientFunction(Function)} to
 * customize.
 * <pre>{@code
 *
 * Retrofit retrofit = new ArmeriaRetrofitBuilder("none+http://localhost:8080/")
 *     .newClientFunction(uri -> new ClientBuilder(uri)
 *             .decorator(HttpRequest.class, HttpResponse.class, LoggingClient::new)
 *             .build(HttpClient.class))
 *     .build();
 * }
 * </pre>
 */
public final class ArmeriaRetrofitBuilder {

    private static final Pattern GROUP_PREFIX_PATTERN = Pattern.compile("^[_0-9a-z]+$");
    private static final String DEFAULT_GROUP_PREFIX = "__group__";
    private static final Function<String, HttpClient> DEFAULT_NEW_CLIENT_FUNCTION =
            url -> Clients.newClient(ClientFactory.DEFAULT, url, HttpClient.class);
    public static final String ROOT_PATH = "/";

    private final Retrofit.Builder retrofitBuilder;
    private String baseUrl;
    private HttpClient baseHttpClient;
    private String basePath;
    private String groupPrefix = DEFAULT_GROUP_PREFIX;
    private Function<String, HttpClient> newClientFunction = DEFAULT_NEW_CLIENT_FUNCTION;

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * url.
     *
     * @see Builder#baseUrl(String)
     */
    public ArmeriaRetrofitBuilder(String baseUrl) {
        if (URI.create(baseUrl).getPath().isEmpty()) {
            throw new IllegalArgumentException("baseUrl must end in /: " + baseUrl);
        }
        this.baseUrl = baseUrl;
        retrofitBuilder = new Retrofit.Builder();
    }

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * {@link HttpClient} instance.
     */
    public ArmeriaRetrofitBuilder(HttpClient baseHttpClient) {
        this(baseHttpClient, ROOT_PATH);
    }

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * {@link HttpClient} instance.
     */
    public ArmeriaRetrofitBuilder(HttpClient baseHttpClient, String basePath) {
        final String path = baseHttpClient.uri().getPath();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must end in /: " + path);
        }
        if (!ROOT_PATH.equals(path)) {
            throw new IllegalArgumentException(
                    "ArmeriaRetrofitBuilder doesn't support http client's uri contains any path element," +
                    " current path: " + path +
                    ". If you want to using uri with path, please using constructor with basePath argument.");
        }
        if (basePath.isEmpty() || !ROOT_PATH.equals(basePath.substring(basePath.length() - 1))) {
            throw new IllegalArgumentException("basePath must end in /: " + basePath);
        }
        this.baseHttpClient = baseHttpClient;
        this.basePath = basePath;
        retrofitBuilder = new Retrofit.Builder();
    }

    /**
     * Set different group name prefix for avoiding conflict of endpoint which starts with __group__.
     */
    public ArmeriaRetrofitBuilder groupPrefix(String groupPrefix) {
        if (!GROUP_PREFIX_PATTERN.matcher(groupPrefix).matches()) {
            throw new IllegalArgumentException(
                    "groupPrefix: " + groupPrefix + " (expected: " + GROUP_PREFIX_PATTERN.pattern() + ')');
        }
        this.groupPrefix = groupPrefix;
        return this;
    }

    /**
     * Set a function for customizing HttpClient.
     */
    public ArmeriaRetrofitBuilder newClientFunction(Function<String, HttpClient> newClientFunction) {
        this.newClientFunction = newClientFunction;
        return this;
    }

    /**
     * @see Retrofit.Builder#addCallAdapterFactory(Factory)
     */
    public ArmeriaRetrofitBuilder addConverterFactory(Converter.Factory factory) {
        retrofitBuilder.addConverterFactory(factory);
        return this;
    }

    /**
     * @see Retrofit.Builder#addCallAdapterFactory(Factory)
     */
    public ArmeriaRetrofitBuilder addCallAdapterFactory(CallAdapter.Factory factory) {
        retrofitBuilder.addCallAdapterFactory(factory);
        return this;
    }

    /**
     * @see Retrofit.Builder#callbackExecutor(Executor)
     */
    public ArmeriaRetrofitBuilder callbackExecutor(Executor executor) {
        retrofitBuilder.callbackExecutor(executor);
        return this;
    }

    /**
     * @see Retrofit.Builder#validateEagerly(boolean)
     */
    public ArmeriaRetrofitBuilder validateEagerly(boolean validateEagerly) {
        retrofitBuilder.validateEagerly(validateEagerly);
        return this;
    }

    /**
     * Create the {@link Retrofit} instance using the configured values.
     */
    public Retrofit build() {
        if (baseUrl != null) {
            final URI uri = URI.create(baseUrl);
            final Scheme scheme = Scheme.parse(uri.getScheme());
            basePath = uri.getPath();
            baseHttpClient = newClientFunction.apply(
                    scheme.uriText() + "://" + uri.getAuthority() + ROOT_PATH);
        }
        return retrofitBuilder.baseUrl(convertToOkHttpUrl(baseHttpClient, basePath, groupPrefix))
                              .callFactory(new ArmeriaCallFactory(baseHttpClient, newClientFunction,
                                                                  groupPrefix))
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
