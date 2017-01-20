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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;

import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.Scheme;

import okhttp3.HttpUrl;
import retrofit2.Retrofit;

/**
 * A helper class for creating a new {@link Retrofit} instance with {@link ArmeriaCallFactory}.
 * For example,
 * <pre>{@code
 * HttpClient httpClient = Clients.newClient(ClientFactory.DEFAULT,
 *                                           "none+http://localhost:8080",
 *                                           HttpClient.class);
 *
 * Retrofit retrofit = ArmeriaRetrofit.builder(httpClient)
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
                                                              .addEscape('@', "_")
                                                              .addEscape('/', "_")
                                                              .addEscape('\\', "_")
                                                              .addEscape('?', "_")
                                                              .addEscape('#', "_")
                                                              .addEscape(':', "_")
                                                              .build();

    /**
     * Creates a {@link Retrofit.Builder} with {@link ArmeriaCallFactory} using the specified
     * {@link HttpClient} instance.
     */
    public static Retrofit.Builder builder(HttpClient httpClient) {
        return new Retrofit.Builder()
                .baseUrl(convertToOkHttpCompatUri(httpClient.uri()))
                .callFactory(new ArmeriaCallFactory(httpClient));
    }

    @VisibleForTesting
    static String convertToOkHttpCompatUri(URI uri) {
        requireNonNull(uri.getScheme(), "uri does not contain the scheme component.");
        String uriStr = uri.toString();

        String protocol = Scheme.tryParse(uri.getScheme())
                                .map(scheme -> scheme.sessionProtocol().uriText())
                                .orElse(uri.getScheme());
        String authority = uri.getAuthority();
        String path = uri.getPath();
        String maybeOkHttpCompatUri = protocol + "://" + authority + path;
        if (HttpUrl.parse(maybeOkHttpCompatUri) == null) {
            return protocol + "://" + GROUP_NAME_ESCAPER.escape(authority) + path;
        } else {
            return maybeOkHttpCompatUri;
        }
    }

    private ArmeriaRetrofit() {}
}
