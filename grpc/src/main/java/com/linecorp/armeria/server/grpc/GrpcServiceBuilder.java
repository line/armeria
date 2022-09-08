/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshallerBuilder;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.protocol.AbstractMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.grpc.HandlerRegistry.Entry;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.protobuf.services.ProtoReflectionService;

/**
 * Constructs a {@link GrpcService} to serve gRPC services from within Armeria.
 */
public final class GrpcServiceBuilder {

    private static final Set<SerializationFormat> DEFAULT_SUPPORTED_SERIALIZATION_FORMATS =
            GrpcSerializationFormats.values();

    private static final Logger logger = LoggerFactory.getLogger(GrpcServiceBuilder.class);

    private static final boolean USE_COROUTINE_CONTEXT_INTERCEPTOR;

    static {
        boolean useCoroutineContextInterceptor;
        final String className =
                BindableService.class.getPackage().getName() +
                ".kotlin.CoroutineContextServerInterceptor";
        try {
            Class.forName(className, false, GrpcServiceBuilder.class.getClassLoader());
            useCoroutineContextInterceptor = true;
        } catch (Throwable ignored) {
            useCoroutineContextInterceptor = false;
        }
        logger.debug("{}: {}", className, useCoroutineContextInterceptor ? "available" : "unavailable");
        USE_COROUTINE_CONTEXT_INTERCEPTOR = useCoroutineContextInterceptor;
    }

    private final HandlerRegistry.Builder registryBuilder = new HandlerRegistry.Builder();

    @Nullable
    private DecompressorRegistry decompressorRegistry;

    @Nullable
    private CompressorRegistry compressorRegistry;

    @Nullable
    private ProtoReflectionServiceInterceptor protoReflectionServiceInterceptor;

    @Nullable
    private LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings;

    @Nullable
    private GrpcStatusFunction statusFunction;

    @Nullable
    private ImmutableList.Builder<ServerInterceptor> interceptors;

    @Nullable
    private UnframedGrpcErrorHandler unframedGrpcErrorHandler;

    @Nullable
    private UnframedGrpcErrorHandler httpJsonTranscodingErrorHandler;

    @Nullable
    private HttpJsonTranscodingOptions httpJsonTranscodingOptions;

    private Set<SerializationFormat> supportedSerializationFormats = DEFAULT_SUPPORTED_SERIALIZATION_FORMATS;

    private int maxRequestMessageLength = AbstractMessageDeframer.NO_MAX_INBOUND_MESSAGE_SIZE;

    private int maxResponseMessageLength = ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE;

    private Function<? super ServiceDescriptor, ? extends GrpcJsonMarshaller> jsonMarshallerFactory =
            GrpcJsonMarshaller::of;

    private boolean enableUnframedRequests;

    private boolean enableHttpJsonTranscoding;

    private boolean useBlockingTaskExecutor;

    private boolean unsafeWrapRequestBuffers;

    private boolean useClientTimeoutHeader = true;

    private boolean enableHealthCheckService;

    private boolean autoCompression;

    @Nullable
    private GrpcHealthCheckService grpcHealthCheckService;

    GrpcServiceBuilder() {}

    /**
     * Adds a gRPC {@link ServerServiceDefinition} to this {@link GrpcServiceBuilder}, such as
     * what's returned by {@link BindableService#bindService()}.
     */
    public GrpcServiceBuilder addService(ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(service, "service"), null, ImmutableList.of());
        return this;
    }

    /**
     * Adds a gRPC {@link ServerServiceDefinition} to this {@link GrpcServiceBuilder}, such as
     * what's returned by {@link BindableService#bindService()}.
     *
     * <p>Note that the specified {@code path} replaces the normal gRPC service path.
     * Let's say you have the following gRPC service definition.
     * <pre>{@code
     * package example.grpc.hello;
     *
     * service HelloService {
     *   rpc Hello (HelloRequest) returns (HelloReply) {}
     * }}</pre>
     * The normal gRPC service path for the {@code Hello} method is
     * {@code "/example.grpc.hello.HelloService/Hello"}.
     * However, if you set {@code "/foo"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/foo/Hello"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(path, "path"), requireNonNull(service, "service"),
                                   null, null, ImmutableList.of());
        return this;
    }

    /**
     * Adds a {@linkplain MethodDescriptor method} of gRPC {@link ServerServiceDefinition} to this
     * {@link GrpcServiceBuilder}. You can get {@link MethodDescriptor}s from the enclosing class of
     * your generated stub.
     *
     * <p>Note that the specified {@code path} replaces the normal gRPC service path.
     * Let's say you have the following gRPC service definition.
     * <pre>{@code
     * package example.grpc.hello;
     *
     * service HelloService {
     *   rpc Hello (HelloRequest) returns (HelloReply) {}
     * }}</pre>
     * The normal gRPC service path for the {@code Hello} method is
     * {@code "/example.grpc.hello.HelloService/Hello"}.
     * However, if you set {@code "/fooHello"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/fooHello"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, ServerServiceDefinition service,
                                         MethodDescriptor<?, ?> methodDescriptor) {
        registryBuilder.addService(requireNonNull(path, "path"),
                                   requireNonNull(service, "service"),
                                   requireNonNull(methodDescriptor, "methodDescriptor"),
                                   null, ImmutableList.of());
        return this;
    }

    /**
     * Adds a gRPC {@link BindableService} to this {@link GrpcServiceBuilder}. Most gRPC service
     * implementations are {@link BindableService}s.
     */
    public GrpcServiceBuilder addService(BindableService bindableService) {
        return addService(bindableService, ImmutableList.of());
    }

    /**
     * Decorates a gRPC {@link BindableService} with the given decorators, in the order of iteration.
     * For more details on decorator behavior, please refer to the following document.
     *
     * @see <a href="https://armeria.dev/docs/server-grpc#decorating-a-grpcservice">Decorating a GrpcService</a>
     */
    @UnstableApi
    public GrpcServiceBuilder addService(
            BindableService bindableService,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(bindableService, "bindableService");
        requireNonNull(decorators, "decorators");

        final boolean hasDecorators = !Iterables.isEmpty(decorators);
        if (bindableService instanceof ProtoReflectionService) {
            if (hasDecorators) {
                throw new IllegalArgumentException(
                        "ProtoReflectionService should not be used with decorators.");
            }
            return addService(ServerInterceptors.intercept(bindableService,
                                                           newProtoReflectionServiceInterceptor()));
        }

        if (bindableService instanceof GrpcHealthCheckService) {
            if (hasDecorators) {
                throw new IllegalArgumentException(
                        "GrpcHealthCheckService should not be used with decorators.");
            }
            if (enableHealthCheckService) {
                throw new IllegalStateException("default gRPC health check service is enabled already.");
            }
            grpcHealthCheckService = (GrpcHealthCheckService) bindableService;
            return this;
        }

        registryBuilder.addService(bindableService.bindService(), bindableService.getClass(),
                                   ImmutableList.copyOf(decorators));
        return this;
    }

    /**
     * Adds a gRPC {@link BindableService} to this {@link GrpcServiceBuilder}. Most gRPC service
     * implementations are {@link BindableService}s.
     *
     * <p>Note that the specified {@code path} replaces the normal gRPC service path.
     * Let's say you have the following gRPC service definition.
     * <pre>{@code
     * package example.grpc.hello;
     *
     * service HelloService {
     *   rpc Hello (HelloRequest) returns (HelloReply) {}
     * }}</pre>
     * The normal gRPC service path for the {@code Hello} method is
     * {@code "/example.grpc.hello.HelloService/Hello"}.
     * However, if you set {@code "/foo"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/foo/Hello"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, BindableService bindableService) {
        return addService(path, bindableService, ImmutableList.of());
    }

    /**
     * Decorates a gRPC {@link BindableService} with the given decorators, in the order of iteration.
     * For more details on decorator behavior, please refer to the following document.
     *
     * @see <a href="https://armeria.dev/docs/server-grpc#decorating-a-grpcservice">Decorating a GrpcService</a>
     */
    @UnstableApi
    public GrpcServiceBuilder addService(
            String path, BindableService bindableService,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(path, "path");
        requireNonNull(bindableService, "bindableService");
        requireNonNull(decorators, "decorators");
        if (bindableService instanceof ProtoReflectionService) {
            if (!Iterables.isEmpty(decorators)) {
                throw new IllegalArgumentException(
                        "ProtoReflectionService should not be used with decorators.");
            }

            return addService(path, ServerInterceptors.intercept(bindableService,
                                                                 newProtoReflectionServiceInterceptor()));
        }
        registryBuilder.addService(path, bindableService.bindService(), null, bindableService.getClass(),
                                   ImmutableList.copyOf(decorators));
        return this;
    }

    /**
     * Adds an implementation of gRPC service to this {@link GrpcServiceBuilder}.
     * Most gRPC service implementations are {@link BindableService}s.
     * This method is useful in cases like the followings.
     *
     * <p>Used for ScalaPB gRPC stubs
     *
     * <pre>{@code
     * GrpcService.builder()
     *            .addService(new HelloServiceImpl,
     *                        HelloServiceGrpc.bindService(_,
     *                                                     ExecutionContext.global))}
     * </pre>
     *
     * <p>Used for intercepted gRPC-Java stubs
     * <pre>{@code
     * GrpcService.builder()
     *            .addService(new TestServiceImpl,
     *                        impl -> ServerInterceptors.intercept(impl, interceptors));
     * }</pre>
     */
    public <T> GrpcServiceBuilder addService(
            T implementation,
            Function<? super T, ServerServiceDefinition> serviceDefinitionFactory) {
        return addService(implementation, serviceDefinitionFactory, ImmutableList.of());
    }

    /**
     * Decorates an implementation of gRPC service with the given decorators, in the order of iteration.
     *
     * <p>Most gRPC service implementations are {@link BindableService}s.
     * This method is useful in cases like the followings.
     *
     * <p>Used for ScalaPB gRPC stubs
     *
     * <pre>{@code
     * GrpcService.builder()
     *            .addService(new HelloServiceImpl,
     *                        HelloServiceGrpc.bindService(_,
     *                                                     ExecutionContext.global))}
     * </pre>
     *
     * <p>Used for intercepted gRPC-Java stubs
     * <pre>{@code
     * GrpcService.builder()
     *            .addService(new TestServiceImpl,
     *                        impl -> ServerInterceptors.intercept(impl, interceptors));
     * }</pre>
     *
     * <p>For more details on decorator behavior, please refer to the following document.
     *
     * @see <a href="https://armeria.dev/docs/server-grpc#decorating-a-grpcservice">Decorating a GrpcService</a>
     */
    @UnstableApi
    public <T> GrpcServiceBuilder addService(
            T implementation,
            Function<? super T, ServerServiceDefinition> serviceDefinitionFactory,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(implementation, "implementation");
        requireNonNull(serviceDefinitionFactory, "serviceDefinitionFactory");
        requireNonNull(decorators, "decorators");

        final ServerServiceDefinition serverServiceDefinition = serviceDefinitionFactory.apply(implementation);
        requireNonNull(serverServiceDefinition, "serviceDefinitionFactory.apply() returned null");
        registryBuilder.addService(serverServiceDefinition, implementation.getClass(),
                                   ImmutableList.copyOf(decorators));
        return this;
    }

    /**
     * Adds a {@linkplain MethodDescriptor method} of gRPC {@link BindableService} to this
     * {@link GrpcServiceBuilder}. You can get {@link MethodDescriptor}s from the enclosing class of
     * your generated stub.
     *
     * <p>Note that the specified {@code path} replaces the normal gRPC service path.
     * Let's say you have the following gRPC service definition.
     * <pre>{@code
     * package example.grpc.hello;
     *
     * service HelloService {
     *   rpc Hello (HelloRequest) returns (HelloReply) {}
     * }}</pre>
     * The normal gRPC service path for the {@code Hello} method is
     * {@code "/example.grpc.hello.HelloService/Hello"}.
     * However, if you set {@code "/fooHello"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/fooHello"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, BindableService bindableService,
                                         MethodDescriptor<?, ?> methodDescriptor) {
        // TODO(minwoox): consider renaming to addMethod(...)
        return addService(path, bindableService, methodDescriptor, ImmutableList.of());
    }

    /**
     * Decorates a {@linkplain MethodDescriptor method} of gRPC {@link BindableService}
     * with the given decorators, in the order of iteration.
     * For more details on decorator behavior, please refer to the following document.
     *
     * @see <a href="https://armeria.dev/docs/server-grpc#decorating-a-grpcservice">Decorating a GrpcService</a>
     */
    @UnstableApi
    public GrpcServiceBuilder addService(
            String path, BindableService bindableService, MethodDescriptor<?, ?> methodDescriptor,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(path, "path");
        requireNonNull(bindableService, "bindableService");
        requireNonNull(methodDescriptor, "methodDescriptor");
        requireNonNull(decorators, "decorators");

        if (bindableService instanceof ProtoReflectionService) {
            if (!Iterables.isEmpty(decorators)) {
                throw new IllegalArgumentException(
                        "ProtoReflectionService should not be used with decorators.");
            }
            final ServerServiceDefinition interceptor =
                    ServerInterceptors.intercept(bindableService, newProtoReflectionServiceInterceptor());
            return addService(path, interceptor, methodDescriptor);
        }

        registryBuilder.addService(path, bindableService.bindService(), methodDescriptor,
                                   bindableService.getClass(), ImmutableList.copyOf(decorators));
        return this;
    }

    /**
     * Adds {@linkplain ServerInterceptor server interceptors} into the gRPC service. The last
     * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
     *
     * @param interceptors array of interceptors to apply to the service.
     */
    public GrpcServiceBuilder intercept(ServerInterceptor... interceptors) {
        requireNonNull(interceptors, "interceptors");
        return intercept(ImmutableList.copyOf(interceptors));
    }

    /**
     * Adds {@linkplain ServerInterceptor server interceptors} into the gRPC service. The last
     * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
     *
     * @param interceptors list of interceptors to apply to the service.
     */
    public GrpcServiceBuilder intercept(Iterable<? extends ServerInterceptor> interceptors) {
        requireNonNull(interceptors, "interceptors");
        interceptors().addAll(interceptors);
        return this;
    }

    private ProtoReflectionServiceInterceptor newProtoReflectionServiceInterceptor() {
        checkState(protoReflectionServiceInterceptor == null,
                   "Attempting to add a ProtoReflectionService but one is already present. " +
                   "ProtoReflectionService must only be added once.");
        return protoReflectionServiceInterceptor = new ProtoReflectionServiceInterceptor();
    }

    /**
     * Adds gRPC {@link BindableService}s to this {@link GrpcServiceBuilder}. Most gRPC service
     * implementations are {@link BindableService}s.
     */
    public GrpcServiceBuilder addServices(BindableService... bindableServices) {
        requireNonNull(bindableServices, "bindableServices");
        return addServices(ImmutableList.copyOf(bindableServices));
    }

    /**
     * Adds gRPC {@link BindableService}s to this {@link GrpcServiceBuilder}. Most gRPC service
     * implementations are {@link BindableService}s.
     */
    public GrpcServiceBuilder addServices(Iterable<BindableService> bindableServices) {
        requireNonNull(bindableServices, "bindableServices");
        bindableServices.forEach(this::addService);
        return this;
    }

    /**
     * Adds gRPC {@link ServerServiceDefinition}s to this {@link GrpcServiceBuilder}.
     */
    public GrpcServiceBuilder addServiceDefinitions(ServerServiceDefinition... services) {
        requireNonNull(services, "services");
        return addServiceDefinitions(ImmutableList.copyOf(services));
    }

    /**
     * Adds gRPC {@link ServerServiceDefinition}s to this {@link GrpcServiceBuilder}.
     */
    public GrpcServiceBuilder addServiceDefinitions(Iterable<ServerServiceDefinition> services) {
        requireNonNull(services, "services");
        services.forEach(this::addService);
        return this;
    }

    /**
     * Sets the {@link DecompressorRegistry} to use when decompressing messages. If not set, will use
     * the default, which supports gzip only.
     */
    public GrpcServiceBuilder decompressorRegistry(DecompressorRegistry registry) {
        decompressorRegistry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Sets the {@link CompressorRegistry} to use when compressing messages. If not set, will use the
     * default, which supports gzip only.
     */
    public GrpcServiceBuilder compressorRegistry(CompressorRegistry registry) {
        compressorRegistry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Sets the {@link SerializationFormat}s supported by this server. If not set, defaults to support
     * all {@link GrpcSerializationFormats#values()}.
     */
    public GrpcServiceBuilder supportedSerializationFormats(SerializationFormat... formats) {
        return supportedSerializationFormats(ImmutableSet.copyOf(requireNonNull(formats, "formats")));
    }

    /**
     * Sets the {@link SerializationFormat}s supported by this server. If not set, defaults to support
     * all {@link GrpcSerializationFormats#values()}.
     */
    public GrpcServiceBuilder supportedSerializationFormats(Iterable<SerializationFormat> formats) {
        requireNonNull(formats, "formats");
        for (SerializationFormat format : formats) {
            if (!GrpcSerializationFormats.isGrpc(format)) {
                throw new IllegalArgumentException("Not a gRPC serialization format: " + format);
            }
        }
        supportedSerializationFormats = ImmutableSet.copyOf(formats);
        return this;
    }

    /**
     * Sets the maximum size in bytes of an individual incoming message. If not set, will use
     * {@link VirtualHost#maxRequestLength()}. To support long-running RPC streams, it is recommended to
     * set {@link ServerBuilder#maxRequestLength(long)}
     * (or {@link VirtualHostBuilder#maxRequestLength(long)})
     * and {@link ServerBuilder#requestTimeoutMillis(long)}
     * (or {@link VirtualHostBuilder#requestTimeoutMillis(long)})
     * to very high values and set this to the expected limit of individual messages in the stream.
     *
     * @deprecated Use {@link #maxRequestMessageLength(int)} instead.
     */
    @Deprecated
    public GrpcServiceBuilder setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        return maxRequestMessageLength(maxInboundMessageSizeBytes);
    }

    /**
     * Sets the maximum size in bytes of an individual outgoing message. If not set, all messages will be sent.
     * This can be a safety valve to prevent overflowing network connections with large messages due to business
     * logic bugs.
     * @deprecated Use {@link #maxResponseMessageLength(int)} instead.
     */
    @Deprecated
    public GrpcServiceBuilder setMaxOutboundMessageSizeBytes(int maxOutboundMessageSizeBytes) {
        return maxResponseMessageLength(maxOutboundMessageSizeBytes);
    }

    /**
     * Sets the maximum size in bytes of an individual request message. If not set, will use
     * {@link VirtualHost#maxRequestLength()}. To support long-running RPC streams, it is recommended to
     * set {@link ServerBuilder#maxRequestLength(long)}
     * (or {@link VirtualHostBuilder#maxRequestLength(long)})
     * and {@link ServerBuilder#requestTimeoutMillis(long)}
     * (or {@link VirtualHostBuilder#requestTimeoutMillis(long)})
     * to very high values and set this to the expected limit of individual messages in the stream.
     */
    public GrpcServiceBuilder maxRequestMessageLength(int maxRequestMessageLength) {
        checkArgument(maxRequestMessageLength > 0,
                      "maxRequestMessageLength: %s (expected: > 0)", maxRequestMessageLength);
        this.maxRequestMessageLength = maxRequestMessageLength;
        return this;
    }

    /**
     * Sets the maximum size in bytes of an individual response message. If not set, all messages will be sent.
     * This can be a safety valve to prevent overflowing network connections with large messages due to business
     * logic bugs.
     */
    public GrpcServiceBuilder maxResponseMessageLength(int maxResponseMessageLength) {
        checkArgument(maxResponseMessageLength > 0,
                      "maxResponseMessageLength: %s (expected: > 0)", maxResponseMessageLength);
        this.maxResponseMessageLength = maxResponseMessageLength;
        return this;
    }

    /**
     * Sets whether the service handles requests not framed using the gRPC wire protocol. Such requests should
     * only have the serialized message as the request content, and the response content will only have the
     * serialized response message. Supporting unframed requests can be useful, for example, when migrating an
     * existing service to gRPC.
     *
     * <p>Limitations:
     * <ul>
     *     <li>Only unary methods (single request, single response) are supported.</li>
     *     <li>
     *         Message compression is not supported.
     *         {@link EncodingService} should be used instead for
     *         transport level encoding.
     *     </li>
     * </ul>
     */
    public GrpcServiceBuilder enableUnframedRequests(boolean enableUnframedRequests) {
        this.enableUnframedRequests = enableUnframedRequests;
        return this;
    }

    /**
     * Set a custom error response mapper. This is useful to serve custom response when using unframed gRPC
     * service.
     * @param unframedGrpcErrorHandler The function which maps the error response to an {@link HttpResponse}.
     */
    @UnstableApi
    public GrpcServiceBuilder unframedGrpcErrorHandler(UnframedGrpcErrorHandler unframedGrpcErrorHandler) {
        requireNonNull(unframedGrpcErrorHandler, "unframedGrpcErrorHandler");
        this.unframedGrpcErrorHandler = unframedGrpcErrorHandler;
        return this;
    }

    /**
     * Sets whether the service handles HTTP/JSON requests using the gRPC wire protocol.
     *
     * <p>Limitations:
     * <ul>
     *     <li>Only unary methods (single request, single response) are supported.</li>
     *     <li>
     *         Message compression is not supported.
     *         {@link EncodingService} should be used instead for
     *         transport level encoding.
     *     </li>
     *     <li>
     *         Transcoding will not work if the {@link GrpcService} is configured with
     *         {@link ServerBuilder#serviceUnder(String, HttpService)}.
     *     </li>
     * </ul>
     *
     * @see <a href="https://cloud.google.com/endpoints/docs/grpc/transcoding">Transcoding HTTP/JSON to gRPC</a>
     */
    @UnstableApi
    public GrpcServiceBuilder enableHttpJsonTranscoding(boolean enableHttpJsonTranscoding) {
        this.enableHttpJsonTranscoding = enableHttpJsonTranscoding;
        return this;
    }

    @UnstableApi
    public GrpcServiceBuilder enableHttpJsonTranscoding(HttpJsonTranscodingOptions httpJsonTranscodingOptions) {
        this.enableHttpJsonTranscoding = true;
        this.httpJsonTranscodingOptions = httpJsonTranscodingOptions;
        return this;
    }

    /**
     * Sets an error handler which handles an exception raised while serving a gRPC request transcoded from
     * an HTTP/JSON request. By default, {@link UnframedGrpcErrorHandler#ofJson()} would be set.
     */
    @UnstableApi
    public GrpcServiceBuilder httpJsonTranscodingErrorHandler(
            UnframedGrpcErrorHandler httpJsonTranscodingErrorHandler) {
        requireNonNull(httpJsonTranscodingErrorHandler, "httpJsonTranscodingErrorHandler");
        this.httpJsonTranscodingErrorHandler = httpJsonTranscodingErrorHandler;
        return this;
    }

    /**
     * Sets whether the service executes service methods using the blocking executor. By default, service
     * methods are executed directly on the event loop for implementing fully asynchronous services. If your
     * service uses blocking logic, you should either execute such logic in a separate thread using something
     * like {@link Executors#newCachedThreadPool()} or enable this setting.
     */
    public GrpcServiceBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    /**
     * Enables unsafe retention of request buffers. Can improve performance when working with very large
     * (i.e., several megabytes) payloads.
     *
     * <p><strong>DISCLAIMER:</strong> Do not use this if you don't know what you are doing. It is very easy to
     * introduce memory leaks when using this method. You will probably spend much time debugging memory leaks
     * during development if this is enabled. You will probably spend much time debugging memory leaks in
     * production if this is enabled. You probably don't want to do this and should turn back now.
     *
     * <p>When enabled, the reference-counted buffer received from the client will be stored into
     * {@link RequestContext} instead of being released. All {@link ByteString} in a
     * protobuf message will reference sections of this buffer instead of having their own copies. When done
     * with a request message, call {@link GrpcUnsafeBufferUtil#releaseBuffer(Object, RequestContext)}
     * with the message and the request's context to release the buffer. The message must be the same
     * reference as what was passed to the service stub - a message with the same contents will not
     * work. If {@link GrpcUnsafeBufferUtil#releaseBuffer(Object, RequestContext)} is not called, the memory
     * will be leaked.
     *
     * <p>Note that this isn't working if the payloads are compressed or the {@link SerializationFormat} is
     * {@link GrpcSerializationFormats#PROTO_WEB_TEXT}.
     */
    public GrpcServiceBuilder unsafeWrapRequestBuffers(boolean unsafeWrapRequestBuffers) {
        this.unsafeWrapRequestBuffers = unsafeWrapRequestBuffers;
        return this;
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
     *       GrpcService.builder()
     *                  .jsonMarshallerFactory(serviceDescriptor -> {
     *                      return GrpcJsonMarshaller.builder()
     *                                               .jsonMarshallerCustomizer(builder -> {
     *                                                    builder.preservingProtoFieldNames(true);
     *                                               })
     *                                               .build(serviceDescriptor);
     *                  })
     *                  .build();
     *       }</pre></li>
     *   <li>Set a customer marshaller for non-{@link Message} types such as {@code scalapb.GeneratedMessage}
     *       for Scala and {@code pbandk.Message} for Kotlin.</li>
     * </ul>
     */
    public GrpcServiceBuilder jsonMarshallerFactory(
            Function<? super ServiceDescriptor, ? extends GrpcJsonMarshaller> jsonMarshallerFactory) {
        this.jsonMarshallerFactory = requireNonNull(jsonMarshallerFactory, "jsonMarshallerFactory");
        return this;
    }

    /**
     * Sets whether to use a {@code grpc-timeout} header sent by the client to control the timeout for request
     * processing. If disabled, the request timeout will be the one configured for the Armeria server, e.g.,
     * using {@link ServerBuilder#requestTimeout(Duration)}.
     *
     * <p>It is recommended to disable this when clients are not trusted code, e.g., for gRPC-Web clients that
     * can come from arbitrary browsers.
     */
    public GrpcServiceBuilder useClientTimeoutHeader(boolean useClientTimeoutHeader) {
        this.useClientTimeoutHeader = useClientTimeoutHeader;
        return this;
    }

    /**
     * Sets the default {@link GrpcHealthCheckService} to this {@link GrpcServiceBuilder}.
     * The gRPC health check service manages only the health checker that determines
     * the healthiness of the {@link Server}.
     *
     * @see
     * <a href="https://github.com/grpc/grpc/blob/master/doc/health-checking.md">GRPC Health Checking Protocol</a>
     */
    public GrpcServiceBuilder enableHealthCheckService(boolean enableHealthCheckService) {
        if (grpcHealthCheckService != null && enableHealthCheckService) {
            throw new IllegalStateException("gRPC health check service is set already.");
        }
        this.enableHealthCheckService = enableHealthCheckService;
        return this;
    }

    /**
     * Sets the specified {@link GrpcStatusFunction} that maps a {@link Throwable} to a gRPC {@link Status}.
     *
     * <p>Note that this method and {@link #addExceptionMapping(Class, Status)} are mutually exclusive.
     */
    public GrpcServiceBuilder exceptionMapping(GrpcStatusFunction statusFunction) {
        requireNonNull(statusFunction, "statusFunction");
        checkState(exceptionMappings == null,
                   "exceptionMapping() and addExceptionMapping() are mutually exclusive.");

        this.statusFunction = statusFunction;
        return this;
    }

    /**
     * Sets whether the gRPC response is compressed automatically when a client sends the
     * {@code grpc-accept-encoding} header with the encoding registered in the {@link CompressorRegistry}.
     */
    @UnstableApi
    public GrpcServiceBuilder autoCompression(boolean autoCompression) {
        this.autoCompression = autoCompression;
        return this;
    }

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     *
     * <p>Note that this method and {@link #exceptionMapping(GrpcStatusFunction)} are mutually exclusive.
     */
    public GrpcServiceBuilder addExceptionMapping(Class<? extends Throwable> exceptionType, Status status) {
        return addExceptionMapping(exceptionType, (ctx, throwable, meta) -> status);
    }

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     *
     * <p>Note that this method and {@link #exceptionMapping(GrpcStatusFunction)} are mutually exclusive.
     *
     * @deprecated Use {@link #addExceptionMapping(Class, GrpcStatusFunction)} instead.
     */
    @Deprecated
    public <T extends Throwable> GrpcServiceBuilder addExceptionMapping(
            Class<T> exceptionType, BiFunction<T, Metadata, Status> statusFunction) {
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(statusFunction, "statusFunction");

        checkState(this.statusFunction == null,
                   "addExceptionMapping() and exceptionMapping() are mutually exclusive.");

        if (exceptionMappings == null) {
            exceptionMappings = new LinkedList<>();
        }

        //noinspection unchecked
        addExceptionMapping(exceptionMappings, exceptionType,
                            (ctx, throwable, metadata) -> statusFunction.apply((T) throwable, metadata));
        return this;
    }

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     *
     * <p>Note that this method and {@link #exceptionMapping(GrpcStatusFunction)} are mutually exclusive.
     */
    public GrpcServiceBuilder addExceptionMapping(Class<? extends Throwable> exceptionType,
                                                  GrpcStatusFunction statusFunction) {
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(statusFunction, "statusFunction");

        checkState(this.statusFunction == null,
                   "addExceptionMapping() and exceptionMapping() are mutually exclusive.");

        if (exceptionMappings == null) {
            exceptionMappings = new LinkedList<>();
        }

        addExceptionMapping(exceptionMappings, exceptionType, statusFunction);
        return this;
    }

    @VisibleForTesting
    static <T extends Throwable> void addExceptionMapping(
            LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings,
            Class<T> exceptionType, GrpcStatusFunction function) {
        requireNonNull(exceptionMappings, "exceptionMappings");
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(function, "function");

        final ListIterator<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> it =
                exceptionMappings.listIterator();

        while (it.hasNext()) {
            final Map.Entry<Class<? extends Throwable>, GrpcStatusFunction> next = it.next();
            final Class<? extends Throwable> oldExceptionType = next.getKey();
            checkArgument(oldExceptionType != exceptionType, "%s is already added with %s",
                          oldExceptionType, next.getValue());

            if (oldExceptionType.isAssignableFrom(exceptionType)) {
                // exceptionType is a subtype of oldExceptionType. exceptionType needs a higher priority.
                it.previous();
                it.add(new SimpleImmutableEntry<>(exceptionType, function));
                return;
            }
        }

        exceptionMappings.add(new SimpleImmutableEntry<>(exceptionType, function));
    }

    /**
     * Converts the specified exception mappings to {@link GrpcStatusFunction}.
     */
    @VisibleForTesting
    static GrpcStatusFunction toGrpcStatusFunction(
            List<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings) {
        final List<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> mappings =
                ImmutableList.copyOf(exceptionMappings);

        return (ctx, throwable, metadata) -> {
            for (Map.Entry<Class<? extends Throwable>, GrpcStatusFunction> mapping : mappings) {
                if (mapping.getKey().isInstance(throwable)) {
                    final Status status = mapping.getValue().apply(ctx, throwable, metadata);
                    return status == null ? null : status.withCause(throwable);
                }
            }
            return null;
        };
    }

    private ImmutableList.Builder<ServerInterceptor> interceptors() {
        if (interceptors == null) {
            interceptors = ImmutableList.builder();
        }
        return interceptors;
    }

    /**
     * Constructs a new {@link GrpcService} that can be bound to
     * {@link ServerBuilder}. It is recommended to bind the service to a server using
     * {@linkplain ServerBuilder#service(HttpServiceWithRoutes, Function[])
     * ServerBuilder.service(HttpServiceWithRoutes)} to mount all service paths
     * without interfering with other services.
     */
    public GrpcService build() {
        final HandlerRegistry handlerRegistry;
        if (USE_COROUTINE_CONTEXT_INTERCEPTOR) {
            final ServerInterceptor coroutineContextInterceptor =
                    new ArmeriaCoroutineContextInterceptor(useBlockingTaskExecutor);
            interceptors().add(coroutineContextInterceptor);
        }
        if (!enableUnframedRequests && unframedGrpcErrorHandler != null) {
            throw new IllegalStateException(
                    "'unframedGrpcErrorHandler' can only be set if unframed requests are enabled");
        }
        if (!enableHttpJsonTranscoding && httpJsonTranscodingErrorHandler != null) {
            throw new IllegalStateException(
                    "'httpJsonTranscodingErrorHandler' can only be set if HTTP/JSON transcoding feature " +
                    "is enabled");
        }
        if (enableHealthCheckService) {
            grpcHealthCheckService = GrpcHealthCheckService.builder().build();
        }
        if (grpcHealthCheckService != null) {
            registryBuilder.addService(grpcHealthCheckService.bindService(), null, ImmutableList.of());
        }
        if (interceptors != null) {
            final HandlerRegistry.Builder newRegistryBuilder = new HandlerRegistry.Builder();
            final ImmutableList<ServerInterceptor> interceptors = this.interceptors.build();
            for (Entry entry : registryBuilder.entries()) {
                final MethodDescriptor<?, ?> methodDescriptor = entry.method();
                final ServerServiceDefinition intercepted =
                        ServerInterceptors.intercept(entry.service(), interceptors);
                newRegistryBuilder.addService(entry.path(), intercepted, methodDescriptor, entry.type(),
                                              entry.additionalDecorators());
            }
            handlerRegistry = newRegistryBuilder.build();
        } else {
            handlerRegistry = registryBuilder.build();
        }

        final GrpcStatusFunction statusFunction;
        if (exceptionMappings != null) {
            statusFunction = toGrpcStatusFunction(exceptionMappings);
        } else {
            statusFunction = this.statusFunction;
        }

        GrpcService grpcService = new FramedGrpcService(
                handlerRegistry,
                firstNonNull(decompressorRegistry, DecompressorRegistry.getDefaultInstance()),
                firstNonNull(compressorRegistry, CompressorRegistry.getDefaultInstance()),
                supportedSerializationFormats,
                jsonMarshallerFactory,
                protoReflectionServiceInterceptor,
                statusFunction,
                maxRequestMessageLength, maxResponseMessageLength,
                useBlockingTaskExecutor,
                unsafeWrapRequestBuffers,
                useClientTimeoutHeader,
                enableHttpJsonTranscoding, // The method definition might be set when transcoding is enabled.
                grpcHealthCheckService,
                autoCompression);
        if (enableUnframedRequests) {
            grpcService = new UnframedGrpcService(
                    grpcService, handlerRegistry,
                    unframedGrpcErrorHandler != null ? unframedGrpcErrorHandler
                                                     : UnframedGrpcErrorHandler.of());
        }
        if (enableHttpJsonTranscoding) {
            grpcService = HttpJsonTranscodingService.of(
                    grpcService,
                    httpJsonTranscodingErrorHandler != null ? httpJsonTranscodingErrorHandler
                                                            : UnframedGrpcErrorHandler.ofJson(),
                    httpJsonTranscodingOptions != null ? httpJsonTranscodingOptions
                                                            : HttpJsonTranscodingOptions.of(false));

        }
        if (handlerRegistry.containsDecorators()) {
            grpcService = new GrpcDecoratingService(grpcService, handlerRegistry);
        }
        return grpcService;
    }
}
