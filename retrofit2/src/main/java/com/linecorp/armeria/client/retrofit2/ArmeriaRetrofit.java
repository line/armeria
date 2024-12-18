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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

import retrofit2.Retrofit;

/**
 * Provides various ways to create a {@link Retrofit} which uses {@link WebClient} for sending requests.
 */
public final class ArmeriaRetrofit {

    /**
     * Returns a new {@link Retrofit} with the specified {@code baseUrl}.
     *
     * @throws IllegalArgumentException if the {@code baseUrl} is not valid or its scheme is not one of
     *                                  the values in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static Retrofit of(String baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Returns a new {@link Retrofit} with the specified {@code baseUrl}.
     *
     * @throws IllegalArgumentException if the {@code baseUrl} is not valid or its scheme is not one of
     *                                  the values in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static Retrofit of(URI baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Returns a new {@link Retrofit} which sends requests to the specified {@link Endpoint} using
     * the specified {@code protocol}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static Retrofit of(String protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link Retrofit} which sends requests to the specified {@link Endpoint} using
     * the specified {@link SessionProtocol}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static Retrofit of(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link Retrofit} which sends requests to the specified {@link Endpoint} using
     * the specified {@code protocol} and {@code path}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static Retrofit of(String protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link Retrofit} which sends requests to the specified {@link Endpoint} using
     * the specified {@link SessionProtocol} and {@code path}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static Retrofit of(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link Retrofit} which is configured with specified {@link HttpPreprocessor}.
     */
    public static Retrofit of(HttpPreprocessor preprocessor) {
        return builder(preprocessor).build();
    }

    /**
     * Returns a new {@link Retrofit} which is configured with specified {@link HttpPreprocessor}
     * and {@code path}.
     */
    public static Retrofit of(HttpPreprocessor preprocessor, String path) {
        return builder(preprocessor, path).build();
    }

    /**
     * Returns a new {@link Retrofit} which sends requests using the specified {@link WebClient}.
     */
    public static Retrofit of(WebClient baseWebClient) {
        return builder(baseWebClient).build();
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} created with the specified {@code baseUrl}.
     *
     * @throws IllegalArgumentException if the {@code baseUrl} is not valid or its scheme is not one of
     *                                  the values in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static ArmeriaRetrofitBuilder builder(String baseUrl) {
        requireNonNull(baseUrl, "baseUrl");
        return builder(WebClient.of(baseUrl));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} created with the specified {@code baseUrl}.
     *
     * @throws IllegalArgumentException if the {@code baseUrl} is not valid or its scheme is not one of
     *                                  the values in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static ArmeriaRetrofitBuilder builder(URI baseUrl) {
        requireNonNull(baseUrl, "baseUrl");
        return builder(WebClient.of(baseUrl));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} that builds a client that sends requests to
     * the specified {@link EndpointGroup} using the specified {@code protocol}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static ArmeriaRetrofitBuilder builder(String protocol, EndpointGroup endpointGroup) {
        return builder(SessionProtocol.of(requireNonNull(protocol, "protocol")), endpointGroup);
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} that builds a client that sends requests to
     * the specified {@link EndpointGroup} using the specified {@link SessionProtocol}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static ArmeriaRetrofitBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        return builder(WebClient.of(protocol, endpointGroup));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} that builds a client that sends requests to
     * the specified {@link EndpointGroup} using the specified {@link SessionProtocol} and {@code path}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static ArmeriaRetrofitBuilder builder(String protocol, EndpointGroup endpointGroup,
                                                 String path) {
        return builder(SessionProtocol.of(requireNonNull(protocol, "protocol")), endpointGroup, path);
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} that builds a client that sends requests to
     * the specified {@link EndpointGroup} using the specified {@link SessionProtocol} and {@code path}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    public static ArmeriaRetrofitBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup,
                                                 String path) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(path, "path");
        return builder(WebClient.of(protocol, endpointGroup, path));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} which is configured with specified
     * {@link HttpPreprocessor}.
     */
    public static ArmeriaRetrofitBuilder builder(HttpPreprocessor httpPreprocessor) {
        return builder(WebClient.of(httpPreprocessor));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} which is configured with specified {@link HttpPreprocessor}
     * and {@code path}.
     */
    public static ArmeriaRetrofitBuilder builder(HttpPreprocessor httpPreprocessor, String path) {
        return builder(WebClient.of(httpPreprocessor, path));
    }

    /**
     * Returns a new {@link ArmeriaRetrofitBuilder} that builds a client that sends requests using
     * the specified {@link WebClient}.
     */
    public static ArmeriaRetrofitBuilder builder(WebClient baseWebClient) {
        requireNonNull(baseWebClient, "baseWebClient");
        checkArgument(!Clients.isUndefinedUri(baseWebClient.uri()),
                      "baseWebClient must have base URL.");
        return new ArmeriaRetrofitBuilder(baseWebClient);
    }

    private ArmeriaRetrofit() {}
}
