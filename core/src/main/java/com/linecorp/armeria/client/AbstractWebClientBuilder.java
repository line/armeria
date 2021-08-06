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
package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal builder implementation for {@link WebClient}.
 */
public abstract class AbstractWebClientBuilder extends AbstractClientOptionsBuilder {

    /**
     * An undefined {@link URI} to create {@link WebClient} without specifying {@link URI}.
     */
    static final URI UNDEFINED_URI = URI.create("http://undefined");

    private static final Set<SessionProtocol> SUPPORTED_PROTOCOLS =
            Sets.immutableEnumSet(
                    ImmutableList.<SessionProtocol>builder().addAll(SessionProtocol.httpValues())
                                                            .addAll(SessionProtocol.httpsValues()).build());

    @Nullable
    private final URI uri;
    @Nullable
    private final EndpointGroup endpointGroup;
    @Nullable
    private final Scheme scheme;
    @Nullable
    private final String path;

    /**
     * Creates a new instance.
     */
    protected AbstractWebClientBuilder() {
        uri = UNDEFINED_URI;
        scheme = null;
        endpointGroup = null;
        path = null;
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    protected AbstractWebClientBuilder(URI uri) {
        if (Clients.isUndefinedUri(uri)) {
            this.uri = uri;
        } else {
            final String givenScheme = requireNonNull(uri, "uri").getScheme();
            final Scheme scheme = validateScheme(givenScheme);
            if (scheme.uriText().equals(givenScheme)) {
                // No need to replace the user-specified scheme because it's already in its normalized form.
                this.uri = uri;
            } else {
                // Replace the user-specified scheme with the normalized one.
                // e.g. http://foo.com/ -> none+http://foo.com/
                this.uri = URI.create(scheme.uriText() +
                                      uri.toString().substring(givenScheme.length()));
            }
        }
        scheme = null;
        endpointGroup = null;
        path = null;
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    protected AbstractWebClientBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                                       @Nullable String path) {
        validateScheme(requireNonNull(sessionProtocol, "sessionProtocol").uriText());
        if (path != null) {
            checkArgument(path.startsWith("/"),
                          "path: %s (expected: an absolute path starting with '/')", path);
        }

        uri = null;
        scheme = Scheme.of(SerializationFormat.NONE, sessionProtocol);
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        this.path = path;
    }

    private static Scheme validateScheme(String scheme) {
        final Scheme parsedScheme = Scheme.tryParse(scheme);
        if (parsedScheme != null) {
            if (parsedScheme.serializationFormat() == SerializationFormat.NONE &&
                SUPPORTED_PROTOCOLS.contains(parsedScheme.sessionProtocol())) {
                return parsedScheme;
            }
        }

        throw new IllegalArgumentException("scheme : " + scheme +
                                           " (expected: one of " + SUPPORTED_PROTOCOLS + ')');
    }

    /**
     * Returns a newly-created web client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link WebClient#builder(String)} or
     *                                  {@link WebClient#builder(URI)} is not an HTTP scheme
     */
    protected final WebClient buildWebClient() {
        final ClientOptions options = buildOptions();
        final ClientBuilderParams params = clientBuilderParams(options);
        final ClientFactory factory = options.factory();
        return (WebClient) factory.newClient(params);
    }

    /**
     * Returns a newly-created {@link ClientBuilderParams} with the specified {@link ClientOptions}.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link WebClient#builder(String)} or
     *                                  {@link WebClient#builder(URI)} is not an HTTP scheme
     */
    protected final ClientBuilderParams clientBuilderParams(ClientOptions options) {
        requireNonNull(options, "options");
        if (uri != null) {
            return ClientBuilderParams.of(uri, WebClient.class, options);
        }

        assert scheme != null;
        assert endpointGroup != null;
        return ClientBuilderParams.of(scheme, endpointGroup, path, WebClient.class, options);
    }

    /**
     * Raises an {@link UnsupportedOperationException} because this builder doesn't support RPC-level but only
     * HTTP-level decorators.
     */
    @Override
    public AbstractWebClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        throw new UnsupportedOperationException("RPC decorator cannot be added to the web client builder.");
    }

    /**
     * Raises an {@link UnsupportedOperationException} because this builder doesn't support RPC-level but only
     * HTTP-level decorators.
     */
    @Override
    public AbstractWebClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        throw new UnsupportedOperationException("RPC decorator cannot be added to the web client builder.");
    }
}
