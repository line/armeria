/*
 * Copyright 2015 LINE Corporation
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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override. If you are creating an
 * {@link HttpClient}, it is recommended to use the {@link HttpClientBuilder} or
 * factory methods in {@link HttpClient}.
 *
 * <h3>How are decorators and HTTP headers configured?</h3>
 *
 * <p>Unlike other options, when a user calls {@link #option(ClientOption, Object)} or {@code options()} with
 * a {@link ClientOption#DECORATION} or a {@link ClientOption#HTTP_HEADERS}, this builder will not simply
 * replace the old option but <em>merge</em> the specified option into the previous option value. For example:
 * <pre>{@code
 * ClientOptionsBuilder b = new ClientOptionsBuilder();
 * b.option(ClientOption.HTTP_HEADERS, headersA);
 * b.option(ClientOption.HTTP_HEADERS, headersB);
 * b.option(ClientOption.DECORATION, decorationA);
 * b.option(ClientOption.DECORATION, decorationB);
 *
 * ClientOptions opts = b.build();
 * HttpHeaders httpHeaders = opts.httpHeaders();
 * ClientDecoration decorations = opts.decoration();
 * }</pre>
 * {@code httpHeaders} will contain all HTTP headers of {@code headersA} and {@code headersB}.
 * If {@code headersA} and {@code headersB} have the headers with the same name, the duplicate header in
 * {@code headerB} will replace the one with the same name in {@code headerA}.
 * Similarly, {@code decorations} will contain all decorators of {@code decorationA} and {@code decorationB},
 * but there will be no replacement but only addition.
 */
public final class ClientBuilder extends AbstractClientOptionsBuilder<ClientBuilder> {

    @Nullable
    private final URI uri;
    @Nullable
    private final Scheme scheme;
    @Nullable
    private final Endpoint endpoint;
    @Nullable
    private final String path;
    private ClientFactory factory = ClientFactory.DEFAULT;

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@code uri}.
     */
    public ClientBuilder(String uri) {
        this(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@link URI}.
     */
    public ClientBuilder(URI uri) {
        this(requireNonNull(uri, "uri"), null, null, null);
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link SessionProtocol} and the {@link SerializationFormat}.
     */
    public ClientBuilder(SessionProtocol protocol, SerializationFormat format, Endpoint endpoint) {
        this(Scheme.of(format, protocol), requireNonNull(endpoint, "endpoint"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the HTTP client that connects to the specified
     * {@link Endpoint} with the {@link SessionProtocol}.
     */
    public ClientBuilder(SessionProtocol protocol, Endpoint endpoint) {
        this(requireNonNull(protocol, "protocol"), SerializationFormat.NONE,
             requireNonNull(endpoint, "endpoint"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@code Scheme}.
     */
    public ClientBuilder(String scheme, Endpoint endpoint) {
        this(Scheme.parse(scheme), requireNonNull(endpoint, "endpoint"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link Scheme}.
     */
    public ClientBuilder(Scheme scheme, Endpoint endpoint) {
        this(null, requireNonNull(scheme, "scheme"), requireNonNull(endpoint, "endpoint"), "");
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link SessionProtocol}, {@link SerializationFormat}, and {@code path}.
     */
    public ClientBuilder(SessionProtocol protocol, SerializationFormat format, Endpoint endpoint, String path) {
        this(Scheme.of(format, protocol), requireNonNull(endpoint, "endpoint"), requireNonNull(path, "path"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the HTTP client that connects to the specified
     * {@link Endpoint} with the {@link SessionProtocol} and {@code path}.
     */
    public ClientBuilder(SessionProtocol protocol, Endpoint endpoint, String path) {
        this(requireNonNull(protocol, "protocol"), SerializationFormat.NONE,
             requireNonNull(endpoint, "endpoint"), requireNonNull(path, "path"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@code scheme} and {@code path}.
     */
    public ClientBuilder(String scheme, Endpoint endpoint, String path) {
        this(Scheme.parse(scheme), requireNonNull(endpoint, "endpoint"), requireNonNull(path, "path"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link Scheme} and {@code path}.
     */
    public ClientBuilder(Scheme scheme, Endpoint endpoint, String path) {
        this(null, requireNonNull(scheme, "scheme"), requireNonNull(endpoint, "endpoint"),
             requireNonNull(path, "path"));
    }

    private ClientBuilder(URI uri, Scheme scheme, Endpoint endpoint, String path) {
        this.uri = uri;
        this.scheme = scheme;
        this.endpoint = endpoint;
        this.path = path;
    }

    /**
     * Sets the {@link ClientFactory} of the client. The default is {@link ClientFactory#DEFAULT}.
     */
    public ClientBuilder factory(ClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Returns a newly-created client which implements the specified {@code clientType}, based on the
     * properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link #ClientBuilder(String)} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     */
    public <T> T build(Class<T> clientType) {
        requireNonNull(clientType, "clientType");

        if (uri != null) {
            return factory.newClient(uri, clientType, buildOptions());
        } else {
            return factory.newClient(scheme, endpoint, path, clientType, buildOptions());
        }
    }
}
