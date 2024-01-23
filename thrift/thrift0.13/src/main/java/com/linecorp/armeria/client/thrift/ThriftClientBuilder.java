/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.thrift.ThriftClientOptions.MAX_RESPONSE_CONTAINER_LENGTH;
import static com.linecorp.armeria.client.thrift.ThriftClientOptions.MAX_RESPONSE_STRING_LENGTH;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;

import com.linecorp.armeria.client.AbstractClientOptionsBuilder;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

/**
 * Creates a new Thrift client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link ThriftClients} if you do not have many options to override.
 */
@UnstableApi
public final class ThriftClientBuilder extends AbstractClientOptionsBuilder {

    @Nullable
    private final EndpointGroup endpointGroup;

    @Nullable
    private URI uri;
    @Nullable
    private String path;
    private Scheme scheme;

    ThriftClientBuilder(URI uri) {
        requireNonNull(uri, "uri");
        checkArgument(uri.getScheme() != null, "uri must have scheme: %s", uri);
        endpointGroup = null;
        this.uri = uri;
        scheme = Scheme.parse(uri.getScheme());
        validateOrSetSerializationFormat();
    }

    ThriftClientBuilder(Scheme scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        uri = null;
        this.scheme = scheme;
        validateOrSetSerializationFormat();
        this.endpointGroup = endpointGroup;
    }

    private void validateOrSetSerializationFormat() {
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            // If not set, TBinary is used as a default serialization format.
            serializationFormat(ThriftSerializationFormats.BINARY);
        } else {
            ensureThriftSerializationFormat(scheme.serializationFormat());
        }
    }

    /**
     * Sets the Thrift {@link SerializationFormat}. If not set, the {@link Scheme#serializationFormat()}
     * specified when creating this builder will be used by default.
     *
     * @see ThriftSerializationFormats
     */
    public ThriftClientBuilder serializationFormat(SerializationFormat serializationFormat) {
        requireNonNull(serializationFormat, "serializationFormat");
        ensureThriftSerializationFormat(serializationFormat);
        scheme = Scheme.of(serializationFormat, scheme.sessionProtocol());
        if (uri != null) {
            final String rawUri = uri.toString();
            uri = URI.create(scheme + rawUri.substring(rawUri.indexOf(':')));
        }
        return this;
    }

    private static void ensureThriftSerializationFormat(SerializationFormat serializationFormat) {
        checkArgument(ThriftSerializationFormats.isThrift(serializationFormat),
                      "serializationFormat: %s (expected: one of %s)",
                      serializationFormat, ThriftSerializationFormats.values());
    }

    /**
     * Sets the path for the Thrift endpoint.
     * This method will be useful if your Thrift service is bound to a path.
     * For example:
     * <pre>{@code
     * // A Thrift service is bound to "/thrift"
     * Server.builder()
     *       .service("/thrift", THttpService.of(new MyThriftService()))
     *       .build();
     *
     * // Set "/thrift" to the Thrift service path.
     * ThriftClients.builder("https://api.example.com")
     *              .path("/thrift")
     *              .build(MyThriftService.XXXIface.class)
     * }</pre>
     */
    public ThriftClientBuilder path(String path) {
        requireNonNull(path, "path");
        checkArgument(!path.isEmpty(), "path is empty.");
        checkArgument(path.charAt(0) == '/', "path: %s (must start with '/')", path);

        this.path = path;
        return this;
    }

    /**
     * Sets the maximum allowed number of bytes to read from the transport for
     * variable-length fields (such as strings or binary).
     * If unspecified, the value of {@link ClientOptions#maxResponseLength()} will be used instead.
     *
     * <p>Note that this option is only valid for {@link TBinaryProtocol} and {@link TCompactProtocol}.
     *
     * @param maxResponseStringLength the maximum allowed string length. {@code 0} disables the length limit.
     */
    public ThriftClientBuilder maxResponseStringLength(int maxResponseStringLength) {
        checkArgument(maxResponseStringLength >= 0, "maxResponseStringLength: %s (expected: >= 0)",
                      maxResponseStringLength);
        return option(MAX_RESPONSE_STRING_LENGTH.newValue(maxResponseStringLength));
    }

    /**
     * Sets the maximum allowed size of containers to read from the transport for maps, sets and lists.
     * If unspecified, the value of {@link ClientOptions#maxResponseLength()} will be used instead.
     *
     * <p>Note that this option is only valid for {@link TBinaryProtocol} and {@link TCompactProtocol}.
     *
     * @param maxResponseContainerLength the maximum allowed string length. {@code 0} disables the length limit.
     */
    public ThriftClientBuilder maxResponseContainerLength(int maxResponseContainerLength) {
        checkArgument(maxResponseContainerLength >= 0, "maxResponseContainerLength: %s (expected: >= 0)",
                      maxResponseContainerLength);
        return option(MAX_RESPONSE_CONTAINER_LENGTH.newValue(maxResponseContainerLength));
    }

    /**
     * Returns a newly-created Thrift client which implements the specified {@code clientType}, based on the
     * properties of this builder.
     */
    public <T> T build(Class<T> clientType) {
        requireNonNull(clientType, "clientType");

        final Object client;
        final ClientOptions options = buildOptions();
        final ClientFactory factory = options.factory();
        URI uri = this.uri;
        if (uri != null) {
            if (path != null) {
                uri = uri.resolve(path);
            }
            client = factory.newClient(ClientBuilderParams.of(uri, clientType, options));
        } else {
            assert endpointGroup != null;
            client = factory.newClient(ClientBuilderParams.of(scheme, endpointGroup,
                                                              path, clientType, options));
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) client;
        return cast;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ThriftClientBuilder options(ClientOptions options) {
        return (ThriftClientBuilder) super.options(options);
    }

    @Override
    public ThriftClientBuilder options(ClientOptionValue<?>... options) {
        return (ThriftClientBuilder) super.options(options);
    }

    @Override
    public ThriftClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (ThriftClientBuilder) super.options(options);
    }

    @Override
    public <T> ThriftClientBuilder option(ClientOption<T> option, T value) {
        return (ThriftClientBuilder) super.option(option, value);
    }

    @Override
    public <T> ThriftClientBuilder option(ClientOptionValue<T> optionValue) {
        return (ThriftClientBuilder) super.option(optionValue);
    }

    @Override
    public ThriftClientBuilder factory(ClientFactory factory) {
        return (ThriftClientBuilder) super.factory(factory);
    }

    @Override
    public ThriftClientBuilder writeTimeout(Duration writeTimeout) {
        return (ThriftClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public ThriftClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (ThriftClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public ThriftClientBuilder responseTimeout(Duration responseTimeout) {
        return (ThriftClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public ThriftClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (ThriftClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public ThriftClientBuilder maxResponseLength(long maxResponseLength) {
        return (ThriftClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public ThriftClientBuilder requestAutoAbortDelay(Duration delay) {
        return (ThriftClientBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public ThriftClientBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (ThriftClientBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public ThriftClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (ThriftClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public ThriftClientBuilder successFunction(SuccessFunction successFunction) {
        return (ThriftClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public ThriftClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (ThriftClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public ThriftClientBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        return (ThriftClientBuilder) super.contextHook(contextHook);
    }

    @Override
    public ThriftClientBuilder decorator(Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (ThriftClientBuilder) super.decorator(decorator);
    }

    @Override
    public ThriftClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (ThriftClientBuilder) super.decorator(decorator);
    }

    @Override
    public ThriftClientBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (ThriftClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ThriftClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (ThriftClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ThriftClientBuilder clearDecorators() {
        return (ThriftClientBuilder) super.clearDecorators();
    }

    @Override
    public ThriftClientBuilder addHeader(CharSequence name, Object value) {
        return (ThriftClientBuilder) super.addHeader(name, value);
    }

    @Override
    public ThriftClientBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ThriftClientBuilder) super.addHeaders(headers);
    }

    @Override
    public ThriftClientBuilder setHeader(CharSequence name, Object value) {
        return (ThriftClientBuilder) super.setHeader(name, value);
    }

    @Override
    public ThriftClientBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ThriftClientBuilder) super.setHeaders(headers);
    }

    @Override
    public ThriftClientBuilder auth(BasicToken token) {
        return (ThriftClientBuilder) super.auth(token);
    }

    @Override
    public ThriftClientBuilder auth(OAuth1aToken token) {
        return (ThriftClientBuilder) super.auth(token);
    }

    @Override
    public ThriftClientBuilder auth(OAuth2Token token) {
        return (ThriftClientBuilder) super.auth(token);
    }

    @Override
    public ThriftClientBuilder auth(AuthToken token) {
        return (ThriftClientBuilder) super.auth(token);
    }

    @Override
    public ThriftClientBuilder followRedirects() {
        return (ThriftClientBuilder) super.followRedirects();
    }

    @Override
    public ThriftClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (ThriftClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public ThriftClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (ThriftClientBuilder) super.contextCustomizer(contextCustomizer);
    }
}
