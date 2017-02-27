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

package com.linecorp.armeria.client.http;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.net.URI;
import java.util.Set;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DefaultClientBuilderParams;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.NonDecoratingClientFactory;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpSessionProtocols;

import io.netty.bootstrap.Bootstrap;

/**
 * A {@link ClientFactory} that creates an HTTP client.
 */
public class HttpClientFactory extends NonDecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            HttpSessionProtocols.values().stream()
                                .map(p -> Scheme.of(SerializationFormat.NONE, p))
                                .collect(toImmutableSet());

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     */
    public HttpClientFactory() {
        this(false);
    }

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    public HttpClientFactory(boolean useDaemonThreads) {
        this(SessionOptions.DEFAULT, useDaemonThreads);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     */
    public HttpClientFactory(SessionOptions options) {
        this(options, false);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    public HttpClientFactory(SessionOptions options, boolean useDaemonThreads) {
        super(options, useDaemonThreads);
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    protected Bootstrap newBootstrap() {
        return super.newBootstrap();
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);

        validateClientType(clientType);

        final Client<HttpRequest, HttpResponse> delegate = options.decoration().decorate(
                HttpRequest.class, HttpResponse.class, new HttpClientDelegate(this));

        if (clientType == Client.class) {
            @SuppressWarnings("unchecked")
            final T castClient = (T) delegate;
            return castClient;
        }

        final Endpoint endpoint = newEndpoint(uri);

        if (clientType == HttpClient.class) {
            final HttpClient client = newHttpClient(uri, scheme, endpoint, options, delegate);

            @SuppressWarnings("unchecked")
            T castClient = (T) client;
            return castClient;
        } else {
            throw new IllegalArgumentException("unsupported client type: " + clientType.getName());
        }
    }

    private DefaultHttpClient newHttpClient(URI uri, Scheme scheme, Endpoint endpoint, ClientOptions options,
                                            Client<HttpRequest, HttpResponse> delegate) {
        return new DefaultHttpClient(
                new DefaultClientBuilderParams(this, uri, HttpClient.class, options),
                delegate, scheme.sessionProtocol(), endpoint);
    }

    private static void validateClientType(Class<?> clientType) {
        if (clientType != HttpClient.class && clientType != Client.class) {
            throw new IllegalArgumentException(
                    "clientType: " + clientType +
                    " (expected: " + HttpClient.class.getSimpleName() + " or " +
                    Client.class.getSimpleName() + ')');
        }
    }
}
