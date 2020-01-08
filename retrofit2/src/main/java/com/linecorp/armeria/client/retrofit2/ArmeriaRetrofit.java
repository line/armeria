/*
 * Copyright 2020 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.WebClient;

import okhttp3.HttpUrl;
import retrofit2.Retrofit;

/**
 * Provides various ways to create a {@link Retrofit} which uses {@link WebClient} for sending requests.
 */
public final class ArmeriaRetrofit {

    /**
     * Returns a new {@link Retrofit} with the specified {@code baseUrl}.
     */
    public static Retrofit of(String baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Returns a new {@link Retrofit} with the specified {@code baseUrl}.
     */
    public static Retrofit of(URI baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Returns a new {@link Retrofit} with the specified {@code baseUrl}.
     */
    public static Retrofit of(HttpUrl baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} created with the specified {@code baseUrl}.
     */
    public static ArmeriaRetrofitBuilder builder(String baseUrl) {
        requireNonNull(baseUrl, "baseUrl");
        return builder(HttpUrl.get(baseUrl));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} created with the specified {@code baseUrl}.
     */
    public static ArmeriaRetrofitBuilder builder(URI baseUrl) {
        requireNonNull(baseUrl, "baseUrl");
        return builder(HttpUrl.get(baseUrl.toString()));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} created with the specified {@code baseUrl}.
     */
    public static ArmeriaRetrofitBuilder builder(HttpUrl baseUrl) {
        requireNonNull(baseUrl, "baseUrl");
        return new ArmeriaRetrofitBuilder(baseUrl);
    }

    private ArmeriaRetrofit() {}
}
