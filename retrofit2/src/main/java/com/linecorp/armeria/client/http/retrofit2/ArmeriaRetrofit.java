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

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;

import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

import okhttp3.HttpUrl;
import retrofit2.Retrofit;
import retrofit2.Retrofit.Builder;

/**
 * A helper class for creating a new {@link Retrofit} instance with {@link ArmeriaCallFactory}.
 * For example,
 * <pre>{@code
 *
 * Retrofit retrofit = ArmeriaRetrofit.builder("none+http://localhost:8080")
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response&lt;User&gt; user = api.getUser().execute();
 * }
 * </pre>
 */
public final class ArmeriaRetrofit {

    private static final Escaper GROUP_NAME_ESCAPER = Escapers.builder()
                                                              .addEscape(':', "_")
                                                              .build();

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * {@link HttpClient} instance.
     *
     * @deprecated Use {@link String} instead.
     */
    @Deprecated
    public static Retrofit.Builder builder(HttpClient httpClient) {
        return builder(httpClient.uri());
    }

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * {@link URI} instance.
     *
     * @see Builder#baseUrl(String)
     */
    public static Retrofit.Builder builder(URI uri) {
        requireNonNull(uri.getScheme(), "uri does not contain the scheme component.");
        SessionProtocol sessionProtocol = Scheme.tryParse(uri.getScheme())
                                                .map(Scheme::sessionProtocol)
                                                .orElseGet(() -> SessionProtocol.of(uri.getScheme()));
        return new Retrofit.Builder()
                .baseUrl(convertToOkHttpUrl(sessionProtocol, uri.getAuthority(), uri.getPath()))
                .callFactory(ArmeriaCallFactory.create(sessionProtocol));
    }

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * url.
     *
     * @see Builder#baseUrl(String)
     */
    public static Retrofit.Builder builder(String url) {
        return builder(URI.create(url));
    }

    private static HttpUrl convertToOkHttpUrl(SessionProtocol sessionProtocol, String authority, String path) {
        String protocol = sessionProtocol.isTls() ? "https" : "http";
        final HttpUrl okHttpUrl = HttpUrl.parse(protocol + "://" + authority + path);
        if (okHttpUrl == null) {
            return HttpUrl.parse(protocol + "://" + GROUP_NAME_ESCAPER.escape(authority) + path);
        } else {
            return okHttpUrl;
        }
    }

    private ArmeriaRetrofit() {}
}
