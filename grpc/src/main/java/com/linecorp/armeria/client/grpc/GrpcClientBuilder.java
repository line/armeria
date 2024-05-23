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

package com.linecorp.armeria.client.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.CALL_CREDENTIALS;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.COMPRESSOR;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.DECOMPRESSOR_REGISTRY;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.EXCEPTION_HANDLER;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.GRPC_JSON_MARSHALLER_FACTORY;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.INTERCEPTORS;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS;
import static com.linecorp.armeria.client.grpc.GrpcClientOptions.USE_METHOD_MARSHALLER;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

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
import com.linecorp.armeria.common.RequestContext;
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
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshallerBuilder;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.common.grpc.UnwrappingGrpcExceptionHandleFunction;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.DecompressorRegistry;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;

/**
 * Creates a new gRPC client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link GrpcClients} if you do not have many options to override.
 */
@UnstableApi
public final class GrpcClientBuilder extends AbstractClientOptionsBuilder {

    private final ImmutableList.Builder<ClientInterceptor> interceptors = ImmutableList.builder();
    @Nullable
    private final EndpointGroup endpointGroup;

    @Nullable
    private URI uri;
    @Nullable
    private String prefix;
    private Scheme scheme;
    @Nullable
    private GrpcExceptionHandlerFunction exceptionHandler;

    GrpcClientBuilder(URI uri) {
        requireNonNull(uri, "uri");
        checkArgument(uri.getScheme() != null, "uri must have scheme: %s", uri);
        endpointGroup = null;
        this.uri = uri;
        scheme = Scheme.parse(uri.getScheme());
        validateOrSetSerializationFormat();
    }

    GrpcClientBuilder(Scheme scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        uri = null;
        this.scheme = scheme;
        validateOrSetSerializationFormat();
        this.endpointGroup = endpointGroup;
    }

    private void validateOrSetSerializationFormat() {
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            // If not set, gRPC protobuf is used as a default serialization format.
            serializationFormat(GrpcSerializationFormats.PROTO);
        } else {
            ensureGrpcSerializationFormat(scheme.serializationFormat());
        }
    }

    /**
     * Sets the gRPC {@link SerializationFormat}. If not set, the {@link Scheme#serializationFormat()}
     * specified when creating this builder will be used by default.
     *
     * @see GrpcSerializationFormats
     */
    public GrpcClientBuilder serializationFormat(SerializationFormat serializationFormat) {
        requireNonNull(serializationFormat, "serializationFormat");
        ensureGrpcSerializationFormat(serializationFormat);
        scheme = Scheme.of(serializationFormat, scheme.sessionProtocol());
        if (uri != null) {
            final String rawUri = uri.toString();
            uri = URI.create(scheme + rawUri.substring(rawUri.indexOf(':')));
        }
        return this;
    }

    private static void ensureGrpcSerializationFormat(SerializationFormat serializationFormat) {
        checkArgument(GrpcSerializationFormats.isGrpc(serializationFormat),
                      "serializationFormat: %s (expected: one of %s)",
                      serializationFormat, GrpcSerializationFormats.values());
    }

    /**
     * Sets the context path for the gRPC endpoint.
     * This method will be useful if your gRPC service is bound to a context path.
     * For example:
     * <pre>{@code
     * // A gRPC service is bound to "/grpc/com.example.MyGrpcService/"
     * Server.builder()
     *       .serviceUnder("/grpc", GrpcService.builder()
     *                                         .addService(new MyGrpcService())
     *                                         .build())
     *       .build();
     *
     * // Prefix "/grpc" to the gRPC service path.
     * GrpcClient.builder("https://api.example.com")
     *           .path("/grpc")
     *           .build(MyGrpcServiceGrpc.XXXStub.class)
     * }</pre>
     *
     * @deprecated Use {@link #pathPrefix(String)} instead.
     */
    @Deprecated
    public GrpcClientBuilder path(String prefix) {
        return pathPrefix(prefix);
    }

    /**
     * Sets the context path for the gRPC endpoint.
     * This method will be useful if your gRPC service is bound to a context path.
     * For example:
     * <pre>{@code
     * // A gRPC service is bound to "/grpc/com.example.MyGrpcService/"
     * Server.builder()
     *       .serviceUnder("/grpc", GrpcService.builder()
     *                                         .addService(new MyGrpcService())
     *                                         .build())
     *       .build();
     *
     * // Prefix "/grpc" to the gRPC service path.
     * GrpcClient.builder("https://api.example.com")
     *           .pathPrefix("/grpc")
     *           .build(MyGrpcServiceGrpc.XXXStub.class)
     * }</pre>
     */
    public GrpcClientBuilder pathPrefix(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(!prefix.isEmpty(), "prefix is empty.");
        checkArgument(prefix.charAt(0) == '/', "prefix: %s (must start with '/')", prefix);

        if (!prefix.endsWith("/")) {
            prefix += '/';
        }
        this.prefix = prefix;
        return this;
    }

    /**
     * Sets the maximum size, in bytes, of messages sent in a request.
     * The default value is {@value ArmeriaMessageFramer#NO_MAX_OUTBOUND_MESSAGE_SIZE},
     * which means unlimited.
     */
    public GrpcClientBuilder maxRequestMessageLength(int maxRequestMessageLength) {
        checkArgument(maxRequestMessageLength >= -1, "maxRequestMessageLength: %s (expected: >= -1)",
                      maxRequestMessageLength);
        return option(MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(maxRequestMessageLength));
    }

    /**
     * Sets the maximum size, in bytes, of messages coming in a response.
     * The default value is {@value ArmeriaMessageDeframer#NO_MAX_INBOUND_MESSAGE_SIZE},
     * which means 'use {@link ClientOptions#MAX_RESPONSE_LENGTH}'.
     */
    public GrpcClientBuilder maxResponseMessageLength(int maxResponseMessageLength) {
        checkArgument(maxResponseMessageLength >= -1, "maxResponseMessageLength: %s (expected: >= -1)",
                      maxResponseMessageLength);
        return option(MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(maxResponseMessageLength));
    }

    /**
     * Sets the {@link Compressor} to use when compressing messages. If not set, {@link Codec.Identity#NONE}
     * will be used by default.
     *
     * <p>Note that it is only safe to call this if the server supports the compression format chosen. There is
     * no negotiation performed; if the server does not support the compression chosen, the call will
     * fail.
     */
    public GrpcClientBuilder compressor(Compressor compressor) {
        requireNonNull(compressor, "compressor");
        return option(COMPRESSOR.newValue(compressor));
    }

    /**
     * Sets the {@link DecompressorRegistry} to use when decompressing messages. If not set, will use
     * the default, which supports gzip only.
     */
    public GrpcClientBuilder decompressorRegistry(DecompressorRegistry registry) {
        requireNonNull(registry, "registry");
        return option(DECOMPRESSOR_REGISTRY.newValue(registry));
    }

    /**
     * Sets the {@link CallCredentials} that carries credential data that will be propagated to the server
     * via request metadata.
     */
    public GrpcClientBuilder callCredentials(CallCredentials callCredentials) {
        requireNonNull(callCredentials, "callCredentials");
        return option(CALL_CREDENTIALS.newValue(callCredentials));
    }

    /**
     * (Advanced users only) Enables unsafe retention of response buffers. Can improve performance when working
     * with very large (i.e., several megabytes) payloads.
     *
     * <p><strong>DISCLAIMER:</strong> Do not use this if you don't know what you are doing. It is very easy to
     * introduce memory leaks when using this method. You will probably spend much time debugging memory leaks
     * during development if this is enabled. You will probably spend much time debugging memory leaks in
     * production if this is enabled. You probably don't want to do this and should turn back now.
     *
     * <p>When enabled, the reference-counted buffer received from the server will be stored into
     * {@link RequestContext} instead of being released. All {@link ByteString} in a
     * protobuf message will reference sections of this buffer instead of having their own copies. When done
     * with a response message, call {@link GrpcUnsafeBufferUtil#releaseBuffer(Object, RequestContext)}
     * with the message and the request's context to release the buffer. The message must be the same
     * reference as what was passed to the client stub - a message with the same contents will not
     * work. If {@link GrpcUnsafeBufferUtil#releaseBuffer(Object, RequestContext)} is not called, the memory
     * will be leaked.
     *
     * <p>Note that this has no effect if the payloads are compressed or the {@link SerializationFormat} is
     * {@link GrpcSerializationFormats#PROTO_WEB_TEXT}.
     */
    @UnstableApi
    public GrpcClientBuilder enableUnsafeWrapResponseBuffers(boolean enableUnsafeWrapResponseBuffers) {
        final ClientOptions options = buildOptions();
        if (options.get(USE_METHOD_MARSHALLER)) {
            throw new IllegalStateException(
                    "'unsafeWrapRequestBuffers' and 'useMethodMarshaller' are mutually exclusive."
            );
        }
        return option(UNSAFE_WRAP_RESPONSE_BUFFERS.newValue(enableUnsafeWrapResponseBuffers));
    }

    /**
     * Sets whether to respect the marshaller specified in gRPC {@link MethodDescriptor}.
     * If disabled, the default marshaller will be used, which is more efficient.
     * This property is disabled by default.
     */
    @UnstableApi
    public GrpcClientBuilder useMethodMarshaller(boolean useMethodMarshaller) {
        final ClientOptions options = buildOptions();
        if (options.get(GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS)) {
            throw new IllegalStateException(
                    "'unsafeWrapRequestBuffers' and 'useMethodMarshaller' are mutually exclusive."
            );
        }
        return option(USE_METHOD_MARSHALLER.newValue(useMethodMarshaller));
    }

    /**
     * Sets the factory that creates a {@link GrpcJsonMarshaller} that serializes and deserializes request or
     * response messages to and from JSON depending on the {@link SerializationFormat}. The returned
     * {@link GrpcJsonMarshaller} from the factory replaces the built-in {@link GrpcJsonMarshaller}.
     *
     * <p>This is commonly used to:
     * <ul>
     *   <li>Switch from the default of using lowerCamelCase for field names to using the field name from
     *       the proto definition, by setting
     *       {@link MessageMarshaller.Builder#preservingProtoFieldNames(boolean)} via
     *       {@link GrpcJsonMarshallerBuilder#jsonMarshallerCustomizer(Consumer)}.
     *       <pre>{@code
     *       GrpcClients.builder(grpcServerUri)
     *                  .jsonMarshallerFactory(serviceDescriptor -> {
     *                      return GrpcJsonMarshaller.builder()
     *                                               .jsonMarshallerCustomizer(builder -> {
     *                                                   builder.preservingProtoFieldNames(true);
     *                                               })
     *                                               .build(serviceDescriptor);
     *                  })
     *                  .build();
     *       }</pre></li>
     *   <li>Set a customer marshaller for non-{@link Message} types such as {@code scalapb.GeneratedMessage}
     *       with {@code com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller} for Scala.
     *       <pre>{@code
     *       GrpcClients.builder(grpcServerUri)
     *                  .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
     *                  .build()
     *       }</pre></li>
     * </ul>
     */
    public GrpcClientBuilder jsonMarshallerFactory(
            Function<? super ServiceDescriptor, ? extends GrpcJsonMarshaller> jsonMarshallerFactory) {
        requireNonNull(jsonMarshallerFactory, "jsonMarshallerFactory");
        return option(GRPC_JSON_MARSHALLER_FACTORY.newValue(jsonMarshallerFactory));
    }

    /**
     * Sets the {@link GrpcClientStubFactory} that creates a gRPC client stub.
     * If not specified, Armeria provides built-in factories for the following gRPC client stubs:
     * <ul>
     *   <li><a href="https://github.com/grpc/grpc-java">gRPC-Java</a></li>
     *   <li><a href="https://github.com/salesforce/reactive-grpc">Reactive-gRPC</a></li>
     *   <li><a href="https://github.com/grpc/grpc-kotlin">gRPC-Kotlin</a></li>
     *   <li><a href="https://scalapb.github.io/">ScalaPB</a></li>
     * </ul>
     */
    public GrpcClientBuilder clientStubFactory(GrpcClientStubFactory clientStubFactory) {
        requireNonNull(clientStubFactory, "clientStubFactory");
        return option(GRPC_CLIENT_STUB_FACTORY.newValue(clientStubFactory));
    }

    /**
     * Adds the {@link ClientInterceptor}s to the gRPC client stub.
     * The specified interceptor(s) is/are executed in reverse order.
     */
    public GrpcClientBuilder intercept(ClientInterceptor... interceptors) {
        requireNonNull(interceptors, "interceptors");
        return intercept(ImmutableList.copyOf(interceptors));
    }

    /**
     * Adds the {@link ClientInterceptor}s to the gRPC client stub.
     * The specified interceptor(s) is/are executed in reverse order.
     */
    public GrpcClientBuilder intercept(Iterable<? extends ClientInterceptor> interceptors) {
        requireNonNull(interceptors, "interceptors");
        this.interceptors.addAll(interceptors);
        return this;
    }

    /**
     * Unsupported operation. {@code rpcDecorator} only supports Thrift.
     * @deprecated Use either {@link #decorator(DecoratingHttpClientFunction)} or
     *             {@link #intercept(ClientInterceptor...)} instead.
     */
    @Deprecated
    @Override
    public GrpcClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        throw new UnsupportedOperationException("rpcDecorator() does not support gRPC. " +
                                                "Use either decorator() or intercept()");
    }

    /**
     * Unsupported operation. {@code rpcDecorator} only supports Thrift.
     * @deprecated Use either {@link #decorator(DecoratingHttpClientFunction)} or
     *             {@link #intercept(ClientInterceptor...)} instead.
     */
    @Deprecated
    @Override
    public GrpcClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        throw new UnsupportedOperationException("rpcDecorator() does not support gRPC. " +
                                                "Use either decorator() or intercept()");
    }

    /**
     * Returns a newly-created gRPC client which implements the specified {@code clientType}, based on the
     * properties of this builder.
     */
    public <T> T build(Class<T> clientType) {
        requireNonNull(clientType, "clientType");

        final List<ClientInterceptor> clientInterceptors = interceptors.build();
        if (!clientInterceptors.isEmpty()) {
            option(INTERCEPTORS.newValue(clientInterceptors));
        }
        if (exceptionHandler != null) {
            option(EXCEPTION_HANDLER.newValue(new UnwrappingGrpcExceptionHandleFunction(exceptionHandler.orElse(
                    GrpcExceptionHandlerFunction.of()))));
        }

        final Object client;
        final ClientOptions options = buildOptions();
        final ClientFactory factory = options.factory();
        URI uri = this.uri;
        if (uri != null) {
            if (prefix != null) {
                uri = uri.resolve(prefix);
            }
            client = factory.newClient(ClientBuilderParams.of(uri, clientType, options));
        } else {
            assert endpointGroup != null;
            client = factory.newClient(ClientBuilderParams.of(scheme, endpointGroup,
                                                              prefix, clientType, options));
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) client;
        return cast;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public GrpcClientBuilder options(ClientOptions options) {
        return (GrpcClientBuilder) super.options(options);
    }

    @Override
    public GrpcClientBuilder options(ClientOptionValue<?>... options) {
        return (GrpcClientBuilder) super.options(options);
    }

    @Override
    public GrpcClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (GrpcClientBuilder) super.options(options);
    }

    @Override
    public <T> GrpcClientBuilder option(ClientOption<T> option, T value) {
        return (GrpcClientBuilder) super.option(option, value);
    }

    @Override
    public <T> GrpcClientBuilder option(ClientOptionValue<T> optionValue) {
        return (GrpcClientBuilder) super.option(optionValue);
    }

    @Override
    public GrpcClientBuilder factory(ClientFactory factory) {
        return (GrpcClientBuilder) super.factory(factory);
    }

    @Override
    public GrpcClientBuilder writeTimeout(Duration writeTimeout) {
        return (GrpcClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public GrpcClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (GrpcClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public GrpcClientBuilder responseTimeout(Duration responseTimeout) {
        return (GrpcClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public GrpcClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (GrpcClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public GrpcClientBuilder maxResponseLength(long maxResponseLength) {
        return (GrpcClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public GrpcClientBuilder requestAutoAbortDelay(Duration delay) {
        return (GrpcClientBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public GrpcClientBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (GrpcClientBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public GrpcClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (GrpcClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public GrpcClientBuilder successFunction(SuccessFunction successFunction) {
        return (GrpcClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public GrpcClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (GrpcClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public GrpcClientBuilder decorator(Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (GrpcClientBuilder) super.decorator(decorator);
    }

    @Override
    public GrpcClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (GrpcClientBuilder) super.decorator(decorator);
    }

    @Override
    public GrpcClientBuilder clearDecorators() {
        return (GrpcClientBuilder) super.clearDecorators();
    }

    @Override
    public GrpcClientBuilder addHeader(CharSequence name, Object value) {
        return (GrpcClientBuilder) super.addHeader(name, value);
    }

    @Override
    public GrpcClientBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (GrpcClientBuilder) super.addHeaders(headers);
    }

    @Override
    public GrpcClientBuilder setHeader(CharSequence name, Object value) {
        return (GrpcClientBuilder) super.setHeader(name, value);
    }

    @Override
    public GrpcClientBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (GrpcClientBuilder) super.setHeaders(headers);
    }

    @Override
    public GrpcClientBuilder auth(BasicToken token) {
        return (GrpcClientBuilder) super.auth(token);
    }

    @Override
    public GrpcClientBuilder auth(OAuth1aToken token) {
        return (GrpcClientBuilder) super.auth(token);
    }

    @Override
    public GrpcClientBuilder auth(OAuth2Token token) {
        return (GrpcClientBuilder) super.auth(token);
    }

    @Override
    public GrpcClientBuilder auth(AuthToken token) {
        return (GrpcClientBuilder) super.auth(token);
    }

    @Override
    public GrpcClientBuilder followRedirects() {
        return (GrpcClientBuilder) super.followRedirects();
    }

    @Override
    public GrpcClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (GrpcClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public GrpcClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (GrpcClientBuilder) super.contextCustomizer(contextCustomizer);
    }

    /**
     * Sets the specified {@link GrpcExceptionHandlerFunction} that maps a {@link Throwable}
     * to a gRPC {@link Status}.
     */
    public GrpcClientBuilder exceptionHandler(GrpcExceptionHandlerFunction exceptionHandler) {
        requireNonNull(exceptionHandler, "exceptionHandler");
        if (this.exceptionHandler == null) {
            this.exceptionHandler = exceptionHandler;
        } else {
            this.exceptionHandler = this.exceptionHandler.orElse(exceptionHandler);
        }
        return this;
    }
}
