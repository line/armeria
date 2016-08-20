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

import java.net.URI;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.NonDecoratingClientFactory;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;

import io.netty.bootstrap.Bootstrap;

/**
 * A {@link ClientFactory} that creates an HTTP client.
 */
public class HttpClientFactory extends NonDecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES;

    static {
        final ImmutableSet.Builder<Scheme> builder = ImmutableSet.builder();
        for (SessionProtocol p : SessionProtocol.ofHttp()) {
            builder.add(Scheme.of(SerializationFormat.NONE, p));
        }
        SUPPORTED_SCHEMES = builder.build();
    }

    private final HttpClientDelegate delegate;

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     */
    public HttpClientFactory() {
        this(SessionOptions.DEFAULT);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     */
    public HttpClientFactory(SessionOptions options) {
        super(options);
        delegate = new HttpClientDelegate(this);
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
            final HttpClient client = new DefaultHttpClient(
                    delegate, eventLoopSupplier(), scheme.sessionProtocol(), options, endpoint);


            @SuppressWarnings("unchecked")
            T castClient = (T) client;
            return castClient;
        } else {
            @SuppressWarnings("deprecation")
            final SimpleHttpClient client = new DefaultSimpleHttpClient(new DefaultHttpClient(
                    delegate, eventLoopSupplier(), scheme.sessionProtocol(), options, endpoint));

            @SuppressWarnings("unchecked")
            T castClient = (T) client;
            return castClient;
        }
    }

    @SuppressWarnings("deprecation")
    private static void validateClientType(Class<?> clientType) {
        if (clientType != HttpClient.class && clientType != SimpleHttpClient.class &&
            clientType != Client.class) {
            throw new IllegalArgumentException(
                    "clientType: " + clientType +
                    " (expected: " + HttpClient.class.getSimpleName() + ", " +
                    SimpleHttpClient.class.getSimpleName() + " or " +
                    Client.class.getSimpleName() + ')');
        }
    }

    @Override
    public void close() {
        delegate.close();
        super.close();
    }
}
