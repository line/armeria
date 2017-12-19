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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Creates a new HTTP client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link HttpClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 */
public final class HttpClientBuilder extends AbstractClientOptionsBuilder<HttpClientBuilder> {

    private final URI uri;
    private ClientFactory factory = ClientFactory.DEFAULT;

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol} or the uri violates RFC 2396
     */
    public HttpClientBuilder(String uri) {
        this(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    public HttpClientBuilder(URI uri) {
        validateScheme(requireNonNull(uri, "uri").getScheme());
        this.uri = URI.create(SerializationFormat.NONE + "+" + uri.toString());
    }

    private static void validateScheme(String scheme) {
        for (SessionProtocol p : SessionProtocol.values()) {
            if (scheme.equalsIgnoreCase(p.uriText())) {
                return;
            }
        }
        throw new IllegalArgumentException("scheme : " + scheme + " (expected: one of " +
                                           ImmutableList.copyOf(SessionProtocol.values()) + ")");
    }

    /**
     * Sets the {@link ClientFactory} of the client. The default is {@link ClientFactory#DEFAULT}.
     */
    public HttpClientBuilder factory(ClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Adds the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     */
    public HttpClientBuilder decorator(
            Function<? extends Client<HttpRequest, HttpResponse>, ? extends Client<HttpRequest, HttpResponse>>
                    decorator) {
        return super.decorator(HttpRequest.class, HttpResponse.class, decorator);
    }

    /**
     * Adds the specified {@code decorator}.
     *
     * @param decorator the {@link DecoratingClientFunction} that intercepts an invocation
     */
    public HttpClientBuilder decorator(DecoratingClientFunction<HttpRequest, HttpResponse> decorator) {
        return super.decorator(HttpRequest.class, HttpResponse.class, decorator);
    }

    /**
     * Returns a newly-created HTTP client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link #HttpClientBuilder(String)} or {@link #HttpClientBuilder(URI)}
     *                                  is not an HTTP scheme
     */
    public HttpClient build() {
        return factory.newClient(uri, HttpClient.class, buildOptions());
    }
}
