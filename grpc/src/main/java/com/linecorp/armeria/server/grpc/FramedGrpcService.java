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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.RouteUtil.innermostRoute;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Codec.Identity;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.netty.util.AttributeKey;

/**
 * The framed {@link GrpcService} implementation.
 */
final class FramedGrpcService extends AbstractHttpService implements GrpcService {

    private static final Logger logger = LoggerFactory.getLogger(FramedGrpcService.class);

    static final AttributeKey<ServerMethodDefinition<?, ?>> RESOLVED_GRPC_METHOD =
            AttributeKey.valueOf(FramedGrpcService.class, "RESOLVED_GRPC_METHOD");

    private final HandlerRegistry registry;
    private final Set<Route> routes;
    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;
    private final Set<SerializationFormat> supportedSerializationFormats;
    private final Map<String, GrpcJsonMarshaller> jsonMarshallers;
    @Nullable
    private final ProtoReflectionServiceInterceptor protoReflectionServiceInterceptor;
    @Nullable
    private final GrpcStatusFunction statusFunction;
    private final int maxResponseMessageLength;
    private final boolean useBlockingTaskExecutor;
    private final boolean unsafeWrapRequestBuffers;
    private final boolean useClientTimeoutHeader;
    private final String advertisedEncodingsHeader;

    private final Map<SerializationFormat, ResponseHeaders> defaultHeaders;

    private int maxRequestMessageLength;
    private boolean lookupMethodFromAttribute;

    FramedGrpcService(HandlerRegistry registry,
                      DecompressorRegistry decompressorRegistry,
                      CompressorRegistry compressorRegistry,
                      Set<SerializationFormat> supportedSerializationFormats,
                      Function<? super ServiceDescriptor, ? extends GrpcJsonMarshaller> jsonMarshallerFactory,
                      @Nullable ProtoReflectionServiceInterceptor protoReflectionServiceInterceptor,
                      @Nullable GrpcStatusFunction statusFunction,
                      int maxRequestMessageLength, int maxResponseMessageLength,
                      boolean useBlockingTaskExecutor,
                      boolean unsafeWrapRequestBuffers,
                      boolean useClientTimeoutHeader,
                      boolean lookupMethodFromAttribute) {
        this.registry = requireNonNull(registry, "registry");
        routes = ImmutableSet.copyOf(registry.methodsByRoute().keySet());
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        this.supportedSerializationFormats = supportedSerializationFormats;
        this.useClientTimeoutHeader = useClientTimeoutHeader;
        if (supportedSerializationFormats.stream().noneMatch(GrpcSerializationFormats::isJson)) {
            jsonMarshallers = ImmutableMap.of();
        } else {
            jsonMarshallers =
                    registry.services().stream()
                            .map(ServerServiceDefinition::getServiceDescriptor)
                            .distinct()
                            .collect(toImmutableMap(ServiceDescriptor::getName, jsonMarshallerFactory));
        }
        this.protoReflectionServiceInterceptor = protoReflectionServiceInterceptor;
        this.statusFunction = statusFunction;
        this.maxRequestMessageLength = maxRequestMessageLength;
        this.maxResponseMessageLength = maxResponseMessageLength;
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        this.unsafeWrapRequestBuffers = unsafeWrapRequestBuffers;
        this.lookupMethodFromAttribute = lookupMethodFromAttribute;

        advertisedEncodingsHeader = String.join(",", decompressorRegistry.getAdvertisedMessageEncodings());

        defaultHeaders = supportedSerializationFormats
                .stream()
                .map(format -> {
                    final ResponseHeadersBuilder builder =
                            ResponseHeaders
                                    .builder(HttpStatus.OK)
                                    .contentType(format.mediaType())
                                    .add(GrpcHeaderNames.GRPC_ENCODING, Identity.NONE.getMessageEncoding());
                    if (!advertisedEncodingsHeader.isEmpty()) {
                        builder.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodingsHeader);
                    }
                    return new SimpleImmutableEntry<>(format, builder.build());
                })
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final MediaType contentType = req.contentType();
        final SerializationFormat serializationFormat = findSerializationFormat(contentType);
        if (serializationFormat == null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Missing or invalid Content-Type header.");
        }

        ctx.logBuilder().serializationFormat(serializationFormat);

        ServerMethodDefinition<?, ?> method = lookupMethodFromAttribute ? ctx.attr(RESOLVED_GRPC_METHOD) : null;
        if (method == null) {
            method = methodDefinition(ctx, registry);
            if (method == null) {
                return HttpResponse.of(
                        (ResponseHeaders) ArmeriaServerCall.statusToTrailers(
                                ctx,
                                defaultHeaders.get(serializationFormat).toBuilder(),
                                Status.UNIMPLEMENTED.withDescription(
                                        "Method not found: " + ctx.config().route().patternString()),
                                new Metadata()));
            }
        }

        if (useClientTimeoutHeader) {
            final String timeoutHeader = req.headers().get(GrpcHeaderNames.GRPC_TIMEOUT);
            if (timeoutHeader != null) {
                try {
                    final long timeout = TimeoutHeaderUtil.fromHeaderValue(timeoutHeader);
                    if (timeout == 0) {
                        ctx.clearRequestTimeout();
                    } else {
                        ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofNanos(timeout));
                    }
                } catch (IllegalArgumentException e) {
                    final Metadata metadata = new Metadata();
                    return HttpResponse.of(
                            (ResponseHeaders) ArmeriaServerCall.statusToTrailers(
                                    ctx, defaultHeaders.get(serializationFormat).toBuilder(),
                                    GrpcStatus.fromThrowable(statusFunction, ctx, e, metadata), metadata));
                }
            }
        }

        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT,
                               RequestLogProperty.RESPONSE_CONTENT);

        final HttpResponseWriter res = HttpResponse.streaming();
        final ArmeriaServerCall<?, ?> call = startCall(
                registry.simpleMethodName(method.getMethodDescriptor()), method, ctx, req, res,
                serializationFormat);
        if (call != null) {
            ctx.whenRequestCancelling().handle((cancellationCause, unused) -> {
                Status status = Status.CANCELLED.withCause(cancellationCause);
                if (cancellationCause instanceof RequestTimeoutException) {
                    status = status.withDescription("Request timed out");
                }
                call.close(status, new Metadata());
                return null;
            });
            call.startDeframing();
        }
        return res;
    }

    @Nullable
    static ServerMethodDefinition<?, ?> methodDefinition(ServiceRequestContext ctx, HandlerRegistry registry) {
        final Route route = innermostRoute(ctx.config().route());
        // method is found using route when the grpcService is set via:
        // - serverBuilder.service(grpcService);
        // - serverBuilder.serviceUnder("/prefix", grpcService);
        final ServerMethodDefinition<?, ?> method = registry.lookupMethod(route);
        if (method != null) {
            return method;
        }
        // method is found using methodName when the grpcService is set via route builder:
        // - serverBuilder.route().pathPrefix("/prefix")...build(grpcService);
        final String methodName = GrpcRequestUtil.determineMethod(ctx);
        if (methodName == null) {
            return null;
        }

        return registry.lookupMethod(methodName);
    }

    @Nullable
    private <I, O> ArmeriaServerCall<I, O> startCall(
            String simpleMethodName,
            ServerMethodDefinition<I, O> methodDef,
            ServiceRequestContext ctx,
            HttpRequest req,
            HttpResponseWriter res,
            SerializationFormat serializationFormat) {
        final MethodDescriptor<I, O> methodDescriptor = methodDef.getMethodDescriptor();
        final ArmeriaServerCall<I, O> call = new ArmeriaServerCall<>(
                req,
                methodDescriptor,
                simpleMethodName,
                compressorRegistry,
                decompressorRegistry,
                res,
                maxRequestMessageLength,
                maxResponseMessageLength,
                ctx,
                serializationFormat,
                jsonMarshallers.get(methodDescriptor.getServiceName()),
                unsafeWrapRequestBuffers,
                useBlockingTaskExecutor,
                defaultHeaders.get(serializationFormat),
                statusFunction);
        final ServerCall.Listener<I> listener;
        try (SafeCloseable ignored = ctx.push()) {
            listener = methodDef.getServerCallHandler()
                                .startCall(call, MetadataUtil.copyFromHeaders(req.headers()));
        } catch (Throwable t) {
            call.setListener(new EmptyListener<>());
            final Metadata metadata = new Metadata();
            call.close(GrpcStatus.fromThrowable(statusFunction, ctx, t, metadata), metadata);
            logger.warn(
                    "Exception thrown from streaming request stub method before processing any request data" +
                    " - this is likely a bug in the stub implementation.", t);
            return null;
        }
        if (listener == null) {
            // This will never happen for normal generated stubs but could conceivably happen for manually
            // constructed ones.
            throw new NullPointerException(
                    "startCall() returned a null listener for method " + methodDescriptor.getFullMethodName());
        }
        call.setListener(listener);
        return call;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) {
        if (maxRequestMessageLength == ArmeriaMessageDeframer.NO_MAX_INBOUND_MESSAGE_SIZE) {
            maxRequestMessageLength = (int) Math.min(cfg.maxRequestLength(), Integer.MAX_VALUE);
        }

        if (protoReflectionServiceInterceptor != null) {
            final Map<String, ServerServiceDefinition> grpcServices =
                    cfg.server().config().virtualHosts().stream()
                       .flatMap(host -> host.serviceConfigs().stream())
                       .map(serviceConfig -> serviceConfig.service().as(FramedGrpcService.class))
                       .filter(Objects::nonNull)
                       .flatMap(service -> service.services().stream())
                       // Armeria allows the same service to be registered multiple times at different
                       // paths, but proto reflection service only supports a single instance of each
                       // service so we dedupe here.
                       .collect(toImmutableMap(def -> def.getServiceDescriptor().getName(),
                                               Function.identity(),
                                               (a, b) -> a));
            protoReflectionServiceInterceptor.setServer(newDummyServer(grpcServices));
        }
    }

    private static Server newDummyServer(Map<String, ServerServiceDefinition> grpcServices) {
        return new Server() {
            @Override
            public Server start() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ServerServiceDefinition> getServices() {
                return ImmutableList.copyOf(grpcServices.values());
            }

            @Override
            public List<ServerServiceDefinition> getImmutableServices() {
                // NB: This will probably go away in favor of just getServices above, so we
                // implement both the same.
                // https://github.com/grpc/grpc-java/issues/4600
                return getServices();
            }

            @Override
            public List<ServerServiceDefinition> getMutableServices() {
                // Armeria does not have the concept of mutable services.
                return ImmutableList.of();
            }

            @Override
            public Server shutdown() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Server shutdownNow() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isShutdown() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isTerminated() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void awaitTermination() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public boolean isFramed() {
        return true;
    }

    @Override
    public List<ServerServiceDefinition> services() {
        final List<ServerServiceDefinition> services = registry.services();
        assert services instanceof ImmutableList;
        return services;
    }

    @Override
    public Map<String, ServerMethodDefinition<?, ?>> methods() {
        return registry.methods();
    }

    @Override
    public Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return registry.methodsByRoute();
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return supportedSerializationFormats;
    }

    @Nullable
    private SerializationFormat findSerializationFormat(@Nullable MediaType contentType) {
        if (contentType == null) {
            return null;
        }

        for (SerializationFormat format : supportedSerializationFormats) {
            if (format.isAccepted(contentType)) {
                return format;
            }
        }

        return null;
    }

    @Override
    public Set<Route> routes() {
        return routes;
    }

    private static class EmptyListener<T> extends ServerCall.Listener<T> {}
}
