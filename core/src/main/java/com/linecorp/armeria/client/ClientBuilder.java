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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override. If you are creating an
 * {@link WebClient}, it is recommended to use the {@link WebClientBuilder} or
 * factory methods in {@link WebClient}.
 *
 * <h3>How are decorators and HTTP headers configured?</h3>
 *
 * <p>Unlike other options, when a user calls {@link #option(ClientOption, Object)} or {@code options()} with
 * a {@link ClientOption#DECORATION} or a {@link ClientOption#HTTP_HEADERS}, this builder will not simply
 * replace the old option but <em>merge</em> the specified option into the previous option value. For example:
 * <pre>{@code
 * ClientOptionsBuilder b = ClientOptions.builder();
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
    private final EndpointGroup endpointGroup;
    @Nullable
    private final Scheme scheme;
    @Nullable
    private final SessionProtocol protocol;
    @Nullable
    private String path;

    private SerializationFormat format = SerializationFormat.NONE;

    private ClientFactory factory = ClientFactory.ofDefault();

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@code uri}.
     *
     * @deprecated Use {@link Clients#builder(String)}.
     */
    @Deprecated
    public ClientBuilder(String uri) {
        this(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@link URI}.
     *
     * @deprecated Use {@link Clients#builder(URI)}.
     */
    @Deprecated
    public ClientBuilder(URI uri) {
        this(requireNonNull(uri, "uri"), null, null, null);
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@code scheme}.
     *
     * @deprecated Use {@link Clients#builder(String, EndpointGroup)}.
     */
    @Deprecated
    public ClientBuilder(String scheme, Endpoint endpointGroup) {
        this(Scheme.parse(requireNonNull(scheme, "scheme")), requireNonNull(endpointGroup, "endpoint"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link Scheme}.
     *
     * @deprecated Use {@link Clients#builder(Scheme, EndpointGroup)}.
     */
    @Deprecated
    public ClientBuilder(Scheme scheme, Endpoint endpointGroup) {
        this(null, requireNonNull(scheme, "scheme"), null, requireNonNull(endpointGroup, "endpoint"));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified
     * {@link Endpoint} with the {@link SessionProtocol}.
     *
     * @deprecated Use {@link Clients#builder(SessionProtocol, EndpointGroup)}.
     */
    @Deprecated
    public ClientBuilder(SessionProtocol protocol, Endpoint endpointGroup) {
        this(null, null, requireNonNull(protocol, "protocol"), requireNonNull(endpointGroup, "endpoint"));
    }

    ClientBuilder(@Nullable URI uri, @Nullable Scheme scheme, @Nullable SessionProtocol protocol,
                  @Nullable EndpointGroup endpointGroup) {
        this.uri = uri;
        this.scheme = scheme;
        this.protocol = protocol;
        this.endpointGroup = endpointGroup;
    }

    /**
     * Sets the {@link ClientFactory} of the client. The default is {@link ClientFactory#ofDefault()}.
     */
    public ClientBuilder factory(ClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@code path} of the client.
     */
    public ClientBuilder path(String path) {
        ensureEndpoint();
        requireNonNull(path, "path");
        checkArgument(path.startsWith("/"), "path: %s (expected: an absolute path)", path);
        this.path = path;
        return this;
    }

    /**
     * Sets the {@link SerializationFormat} of the client. The default is {@link SerializationFormat#NONE}.
     */
    public ClientBuilder serializationFormat(SerializationFormat format) {
        ensureEndpoint();
        if (scheme != null) {
            throw new IllegalStateException("scheme is already given");
        }

        this.format = requireNonNull(format, "format");
        return this;
    }

    /**
     * Returns a newly-created client which implements the specified {@code clientType}, based on the
     * properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link Clients#builder(String)} or the specified {@code clientType} is
     *                                  unsupported for the scheme
     */
    public <T> T build(Class<T> clientType) {
        requireNonNull(clientType, "clientType");

        final Object client;
        if (uri != null) {
            client = factory.newClient(ClientBuilderParams.of(factory, uri, clientType, buildOptions()));
        } else {
            assert endpointGroup != null;
            client = factory.newClient(ClientBuilderParams.of(factory, scheme(), endpointGroup,
                                                              path, clientType, buildOptions()));
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) client;
        return cast;
    }

    private Scheme scheme() {
        return scheme == null ? Scheme.of(format, protocol) : scheme;
    }

    private void ensureEndpoint() {
        if (endpointGroup == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " must be created with an " + Endpoint.class.getSimpleName() +
                    " to call this method.");
        }
    }
}
