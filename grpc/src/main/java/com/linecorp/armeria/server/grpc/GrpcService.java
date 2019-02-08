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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcJsonUtil;
import com.linecorp.armeria.internal.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceWithPathMappings;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;

/**
 * A {@link AbstractHttpService} that implements the gRPC wire protocol. Interfaces and binding logic of gRPC
 * generated stubs are supported, however compatibility with gRPC's core java API is best effort.
 *
 * <p>Unsupported features:
 * <ul>
 *     <li>
 *         {@link Metadata} - use armeria's HttpHeaders and decorators for accessing custom metadata sent from
 *         the client. Any usages of {@link Metadata} in the server will be silently ignored.
 *     </li>
 *     <li>
 *         There are some differences in the HTTP/2 error code returned from an Armeria server vs gRPC server
 *         when dealing with transport errors and deadlines. Generally, the client will see an UNKNOWN status
 *         when the official server may have returned CANCELED.
 *     </li>
 * </ul>
 */
public final class GrpcService extends AbstractHttpService
        implements ServiceWithPathMappings<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(GrpcService.class);

    static final int NO_MAX_INBOUND_MESSAGE_SIZE = -1;

    private static final Metadata EMPTY_METADATA = new Metadata();

    private final HandlerRegistry registry;
    private final Set<PathMapping> pathMappings;
    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;
    private final Set<SerializationFormat> supportedSerializationFormats;
    @Nullable private final MessageMarshaller jsonMarshaller;
    private final int maxOutboundMessageSizeBytes;
    private final boolean useBlockingTaskExecutor;
    private final boolean unsafeWrapRequestBuffers;
    private final String advertisedEncodingsHeader;

    private int maxInboundMessageSizeBytes;

    GrpcService(HandlerRegistry registry,
                Set<PathMapping> pathMappings,
                DecompressorRegistry decompressorRegistry,
                CompressorRegistry compressorRegistry,
                Set<SerializationFormat> supportedSerializationFormats,
                int maxOutboundMessageSizeBytes,
                boolean useBlockingTaskExecutor,
                boolean unsafeWrapRequestBuffers,
                int maxInboundMessageSizeBytes) {
        this.registry = requireNonNull(registry, "registry");
        this.pathMappings = requireNonNull(pathMappings, "pathMappings");
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        this.supportedSerializationFormats = supportedSerializationFormats;
        jsonMarshaller = jsonMarshaller(registry, supportedSerializationFormats);
        this.maxOutboundMessageSizeBytes = maxOutboundMessageSizeBytes;
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        this.unsafeWrapRequestBuffers = unsafeWrapRequestBuffers;
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;

        advertisedEncodingsHeader = String.join(",", decompressorRegistry.getAdvertisedMessageEncodings());
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

        final String methodName = GrpcRequestUtil.determineMethod(ctx);
        if (methodName == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Invalid path.");
        }

        final ServerMethodDefinition<?, ?> method = registry.lookupMethod(methodName);
        if (method == null) {
            return HttpResponse.of(
                    ArmeriaServerCall.statusToTrailers(
                            ctx,
                            Status.UNIMPLEMENTED.withDescription("Method not found: " + methodName),
                            false));
        }

        final String timeoutHeader = req.headers().get(GrpcHeaderNames.GRPC_TIMEOUT);
        if (timeoutHeader != null) {
            try {
                final long timeout = TimeoutHeaderUtil.fromHeaderValue(timeoutHeader);
                ctx.setRequestTimeout(Duration.ofNanos(timeout));
            } catch (IllegalArgumentException e) {
                return HttpResponse.of(ArmeriaServerCall.statusToTrailers(ctx, Status.fromThrowable(e), false));
            }
        }

        ctx.logBuilder().deferRequestContent();
        ctx.logBuilder().deferResponseContent();

        final HttpResponseWriter res = HttpResponse.streaming();
        final ArmeriaServerCall<?, ?> call = startCall(
                methodName, method, ctx, req.headers(), res, serializationFormat);
        if (call != null) {
            ctx.setRequestTimeoutHandler(() -> call.close(Status.DEADLINE_EXCEEDED, EMPTY_METADATA));
            req.subscribe(call.messageReader(), ctx.eventLoop(), true);
            req.completionFuture().handleAsync(call.messageReader(), ctx.eventLoop());
        }
        return res;
    }

    @Nullable
    private <I, O> ArmeriaServerCall<I, O> startCall(
            String fullMethodName,
            ServerMethodDefinition<I, O> methodDef,
            ServiceRequestContext ctx,
            HttpHeaders headers,
            HttpResponseWriter res,
            SerializationFormat serializationFormat) {
        final ArmeriaServerCall<I, O> call = new ArmeriaServerCall<>(
                headers,
                methodDef.getMethodDescriptor(),
                compressorRegistry,
                decompressorRegistry,
                res,
                maxInboundMessageSizeBytes,
                maxOutboundMessageSizeBytes,
                ctx,
                serializationFormat,
                jsonMarshaller,
                unsafeWrapRequestBuffers,
                useBlockingTaskExecutor,
                advertisedEncodingsHeader);
        final ServerCall.Listener<I> listener;
        try (SafeCloseable ignored = ctx.push()) {
            listener = methodDef.getServerCallHandler().startCall(call, EMPTY_METADATA);
        } catch (Throwable t) {
            call.setListener(new EmptyListener<>());
            call.close(Status.fromThrowable(t), EMPTY_METADATA);
            logger.warn(
                    "Exception thrown from streaming request stub method before processing any request data" +
                    " - this is likely a bug in the stub implementation.");
            return null;
        }
        if (listener == null) {
            // This will never happen for normal generated stubs but could conceivably happen for manually
            // constructed ones.
            throw new NullPointerException(
                    "startCall() returned a null listener for method " + fullMethodName);
        }
        call.setListener(listener);
        return call;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) {
        if (maxInboundMessageSizeBytes == NO_MAX_INBOUND_MESSAGE_SIZE) {
            maxInboundMessageSizeBytes = (int) Math.min(cfg.server().config().defaultMaxRequestLength(),
                                                        Integer.MAX_VALUE);
        }
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, PathMapping pathMapping) {
        // gRPC services always have a single path per method that is safe to cache.
        return true;
    }

    List<ServerServiceDefinition> services() {
        return registry.services();
    }

    Set<SerializationFormat> supportedSerializationFormats() {
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

    @Nullable
    private static MessageMarshaller jsonMarshaller(HandlerRegistry registry,
                                                    Set<SerializationFormat> supportedSerializationFormats) {
        if (supportedSerializationFormats.stream().noneMatch(GrpcSerializationFormats::isJson)) {
            return null;
        }
        final List<MethodDescriptor<?, ?>> methods =
                registry.services().stream()
                        .flatMap(service -> service.getMethods().stream())
                        .map(ServerMethodDefinition::getMethodDescriptor)
                        .collect(toImmutableList());
        return GrpcJsonUtil.jsonMarshaller(methods);
    }

    @Override
    public Set<PathMapping> pathMappings() {
        return pathMappings;
    }

    private static class EmptyListener<T> extends ServerCall.Listener<T> {}
}
