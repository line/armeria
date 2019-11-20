/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Creates a new web client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link WebClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 */
public final class WebClientBuilder extends AbstractClientOptionsBuilder<WebClientBuilder> {

    /**
     * An undefined {@link URI} to create {@link WebClient} without specifying {@link URI}.
     */
    private static final URI UNDEFINED_URI = URI.create("http://undefined");

    /**
     * Returns {@code true} if the specified {@code uri} is an undefined {@link URI}.
     */
    static boolean isUndefinedUri(URI uri) {
        return UNDEFINED_URI == uri;
    }

    @Nullable
    private final URI uri;
    @Nullable
    private final Endpoint endpoint;
    @Nullable
    private final Scheme scheme;
    @Nullable
    private String path;
    private ClientFactory factory = ClientFactory.ofDefault();

    /**
     * Creates a new instance.
     */
    WebClientBuilder() {
        uri = UNDEFINED_URI;
        scheme = null;
        endpoint = null;
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    WebClientBuilder(URI uri) {
        if (isUndefinedUri(uri)) {
            this.uri = uri;
        } else {
            validateScheme(requireNonNull(uri, "uri").getScheme());
            this.uri = URI.create(SerializationFormat.NONE + "+" + uri);
        }
        scheme = null;
        endpoint = null;
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    WebClientBuilder(SessionProtocol sessionProtocol, Endpoint endpoint) {
        validateScheme(requireNonNull(sessionProtocol, "sessionProtocol").uriText());

        uri = null;
        scheme = Scheme.of(SerializationFormat.NONE, sessionProtocol);
        this.endpoint = requireNonNull(endpoint, "endpoint");
    }

    private static void validateScheme(String scheme) {
        for (SessionProtocol p : SessionProtocol.values()) {
            if (scheme.equalsIgnoreCase(p.uriText())) {
                return;
            }
        }
        throw new IllegalArgumentException("scheme : " + scheme + " (expected: one of " +
                                           ImmutableList.copyOf(SessionProtocol.values()) + ')');
    }

    /**
     * Sets the {@link ClientFactory} of the client. The default is {@link ClientFactory#ofDefault()}.
     */
    public WebClientBuilder factory(ClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@code path} of the client.
     */
    public WebClientBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Returns a newly-created web client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link WebClient#builder(String)} or
     *                                  {@link WebClient#builder(URI)} is not an HTTP scheme
     */
    public WebClient build() {
        if (uri != null) {
            return factory.newClient(uri, WebClient.class, buildOptions());
        } else if (path != null) {
            return factory.newClient(scheme, endpoint, path, WebClient.class, buildOptions());
        } else {
            return factory.newClient(scheme, endpoint, WebClient.class, buildOptions());
        }
    }

    @Override
    public WebClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        throw new UnsupportedOperationException("RPC decorator cannot be added to the web client builder.");
    }

    @Override
    public WebClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        throw new UnsupportedOperationException("RPC decorator cannot be added to the web client builder.");
    }
}
