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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Creates a new web client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link WebClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 */
public final class WebClientBuilder extends AbstractClientOptionsBuilder {

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
    WebClientBuilder() {
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
    WebClientBuilder(URI uri) {
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
    WebClientBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup, @Nullable String path) {
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
    public WebClient build() {
        final ClientOptions options = buildOptions();
        final ClientFactory factory = options.factory();
        final ClientBuilderParams params;

        if (uri != null) {
            params = ClientBuilderParams.of(uri, WebClient.class, options);
        } else {
            assert scheme != null;
            assert endpointGroup != null;
            params = ClientBuilderParams.of(scheme, endpointGroup, path, WebClient.class, options);
        }

        return (WebClient) factory.newClient(params);
    }

    @Override
    public WebClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        throw new UnsupportedOperationException("RPC decorator cannot be added to the web client builder.");
    }

    @Override
    public WebClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        throw new UnsupportedOperationException("RPC decorator cannot be added to the web client builder.");
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public WebClientBuilder options(ClientOptions options) {
        return (WebClientBuilder) super.options(options);
    }

    @Override
    public WebClientBuilder options(ClientOptionValue<?>... options) {
        return (WebClientBuilder) super.options(options);
    }

    @Override
    public WebClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (WebClientBuilder) super.options(options);
    }

    @Override
    public <T> WebClientBuilder option(ClientOption<T> option, T value) {
        return (WebClientBuilder) super.option(option, value);
    }

    @Override
    public <T> WebClientBuilder option(ClientOptionValue<T> optionValue) {
        return (WebClientBuilder) super.option(optionValue);
    }

    @Override
    public WebClientBuilder factory(ClientFactory factory) {
        return (WebClientBuilder) super.factory(factory);
    }

    @Override
    public WebClientBuilder writeTimeout(Duration writeTimeout) {
        return (WebClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public WebClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (WebClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public WebClientBuilder responseTimeout(Duration responseTimeout) {
        return (WebClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public WebClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (WebClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public WebClientBuilder maxResponseLength(long maxResponseLength) {
        return (WebClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public WebClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (WebClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public WebClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (WebClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public WebClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (WebClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (WebClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebClientBuilder addHttpHeader(CharSequence name, Object value) {
        return (WebClientBuilder) super.addHttpHeader(name, value);
    }

    @Override
    public WebClientBuilder addHttpHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> httpHeaders) {
        return (WebClientBuilder) super.addHttpHeaders(httpHeaders);
    }

    @Override
    public WebClientBuilder setHttpHeader(CharSequence name, Object value) {
        return (WebClientBuilder) super.setHttpHeader(name, value);
    }

    @Override
    public WebClientBuilder setHttpHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> httpHeaders) {
        return (WebClientBuilder) super.setHttpHeaders(httpHeaders);
    }
}
