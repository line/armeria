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
import static com.linecorp.armeria.internal.common.grpc.GrpcStatus.toGrpcStatusFunction;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshallerBuilder;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.grpc.HandlerRegistry.Entry;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
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
        final String className = "io.grpc.kotlin.CoroutineContextServerInterceptor";
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
    private LinkedList<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings;

    @Nullable
    private GrpcStatusFunction exceptionMappingFunction;

    private Set<SerializationFormat> supportedSerializationFormats = DEFAULT_SUPPORTED_SERIALIZATION_FORMATS;

    private int maxInboundMessageSizeBytes = ArmeriaMessageDeframerHandler.NO_MAX_INBOUND_MESSAGE_SIZE;

    private int maxOutboundMessageSizeBytes = ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE;

    private Function<? super ServiceDescriptor, ? extends GrpcJsonMarshaller> jsonMarshallerFactory =
            GrpcJsonMarshaller::of;

    private boolean enableUnframedRequests;

    private boolean useBlockingTaskExecutor;

    private boolean unsafeWrapRequestBuffers;

    private boolean useClientTimeoutHeader = true;

    GrpcServiceBuilder() {}

    /**
     * Adds a gRPC {@link ServerServiceDefinition} to this {@link GrpcServiceBuilder}, such as
     * what's returned by {@link BindableService#bindService()}.
     */
    public GrpcServiceBuilder addService(ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(service, "service"));
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
     * However if you set {@code "/foo"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/foo/Hello"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(path, "path"), requireNonNull(service, "service"), null);
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
     * However if you set {@code "/foo"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/foo"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, ServerServiceDefinition service,
                                         MethodDescriptor<?, ?> methodDescriptor) {
        registryBuilder.addService(requireNonNull(path, "path"),
                                   requireNonNull(service, "service"),
                                   requireNonNull(methodDescriptor, "methodDescriptor"));
        return this;
    }

    /**
     * Adds a gRPC {@link BindableService} to this {@link GrpcServiceBuilder}. Most gRPC service
     * implementations are {@link BindableService}s.
     */
    public GrpcServiceBuilder addService(BindableService bindableService) {
        if (bindableService instanceof ProtoReflectionService) {
            return addService(ServerInterceptors.intercept(bindableService,
                                                           newProtoReflectionServiceInterceptor()));
        }

        return addService(bindableService.bindService());
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
     * However if you set {@code "/foo"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/foo/Hello"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, BindableService bindableService) {
        if (bindableService instanceof ProtoReflectionService) {
            return addService(path, ServerInterceptors.intercept(bindableService,
                                                                 newProtoReflectionServiceInterceptor()));
        }

        return addService(path, bindableService.bindService());
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
     * However if you set {@code "/foo"} to {@code path}, the {@code Hello} method will be served at
     * {@code "/foo"}. This is useful for supporting unframed gRPC with HTTP idiomatic path.
     */
    public GrpcServiceBuilder addService(String path, BindableService bindableService,
                                         MethodDescriptor<?, ?> methodDescriptor) {
        if (bindableService instanceof ProtoReflectionService) {
            final ServerServiceDefinition interceptor =
                    ServerInterceptors.intercept(bindableService, newProtoReflectionServiceInterceptor());
            return addService(path, interceptor, methodDescriptor);
        }

        return addService(path, bindableService.bindService(), methodDescriptor);
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
     */
    public GrpcServiceBuilder setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        checkArgument(maxInboundMessageSizeBytes > 0,
                      "maxInboundMessageSizeBytes must be >0");
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
        return this;
    }

    /**
     * Sets the maximum size in bytes of an individual outgoing message. If not set, all messages will be sent.
     * This can be a safety valve to prevent overflowing network connections with large messages due to business
     * logic bugs.
     */
    public GrpcServiceBuilder setMaxOutboundMessageSizeBytes(int maxOutboundMessageSizeBytes) {
        checkArgument(maxOutboundMessageSizeBytes > 0,
                      "maxOutboundMessageSizeBytes must be >0");
        this.maxOutboundMessageSizeBytes = maxOutboundMessageSizeBytes;
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
     * Sets the specified {@link GrpcStatusFunction} that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping function are used to handle a {@link Throwable} when it is raised.
     *
     * <p>Note that this method and {@link #addExceptionMapping(Class, Status)} are mutually exclusive.
     */
    public GrpcServiceBuilder exceptionMapping(
            GrpcStatusFunction exceptionMapping) {
        requireNonNull(exceptionMapping, "exceptionMapping");
        checkState(exceptionMappings == null,
                   "exceptionMapping() and addExceptionMapping() are mutually exclusive.");

        exceptionMappingFunction = exceptionMapping;
        return this;
    }

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     *
     * <p>Note that this method and {@link #exceptionMapping(GrpcStatusFunction)} are mutually exclusive.
     */
    public GrpcServiceBuilder addExceptionMapping(Class<? extends Throwable> exceptionType, Status status) {
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(status, "status");

        checkState(exceptionMappingFunction == null,
                   "addExceptionMapping() and exceptionMapping() are mutually exclusive.");

        if (exceptionMappings == null) {
            exceptionMappings = new LinkedList<>();
            exceptionMappings.add(new SimpleImmutableEntry<>(exceptionType, status));
            return this;
        }

        addExceptionMapping(exceptionMappings, exceptionType, status);
        return this;
    }

    @VisibleForTesting
    static void addExceptionMapping(
            LinkedList<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings,
            Class<? extends Throwable> exceptionType, Status status) {
        requireNonNull(exceptionMappings, "exceptionMappings");
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(status, "status");

        final ListIterator<Map.Entry<Class<? extends Throwable>, Status>> it =
                exceptionMappings.listIterator();

        while (it.hasNext()) {
            final Map.Entry<Class<? extends Throwable>, Status> next = it.next();
            final Class<? extends Throwable> oldExceptionType = next.getKey();
            checkArgument(oldExceptionType != exceptionType, "%s is already added with %s",
                          oldExceptionType, next.getValue());

            if (oldExceptionType.isAssignableFrom(exceptionType)) {
                // exceptionType is a subtype of oldExceptionType. exceptionType needs a higher priority.
                it.previous();
                it.add(new SimpleImmutableEntry<>(exceptionType, status));
                return;
            }
        }

        exceptionMappings.add(new SimpleImmutableEntry<>(exceptionType, status));
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
            final HandlerRegistry.Builder newRegistryBuilder = new HandlerRegistry.Builder();

            for (Entry entry : registryBuilder.entries()) {
                final MethodDescriptor<?, ?> methodDescriptor = entry.method();
                final ServerServiceDefinition intercepted =
                        ServerInterceptors.intercept(entry.service(), coroutineContextInterceptor);
                newRegistryBuilder.addService(entry.path(), intercepted, methodDescriptor);
            }
            handlerRegistry = newRegistryBuilder.build();
        } else {
            handlerRegistry = registryBuilder.build();
        }

        if (exceptionMappings != null) {
            exceptionMappingFunction = toGrpcStatusFunction(exceptionMappings);
        }

        final GrpcService grpcService = new FramedGrpcService(
                handlerRegistry,
                handlerRegistry
                        .methods()
                        .keySet()
                        .stream()
                        .map(path -> Route.builder().exact('/' + path).build())
                        .collect(ImmutableSet.toImmutableSet()),
                firstNonNull(decompressorRegistry, DecompressorRegistry.getDefaultInstance()),
                firstNonNull(compressorRegistry, CompressorRegistry.getDefaultInstance()),
                supportedSerializationFormats,
                jsonMarshallerFactory,
                protoReflectionServiceInterceptor,
                exceptionMappingFunction,
                maxOutboundMessageSizeBytes,
                useBlockingTaskExecutor,
                unsafeWrapRequestBuffers,
                useClientTimeoutHeader,
                maxInboundMessageSizeBytes);
        return enableUnframedRequests ? new UnframedGrpcService(grpcService, handlerRegistry) : grpcService;
    }
}
