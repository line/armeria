/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;

/**
 * A {@link AbstractHttpService} that implements the GRPC wire protocol. Interfaces and binding logic of GRPC
 * generated stubs are supported, however compatibility with GRPC's core java API is best effort.
 *
 * <p>Unsupported features:
 * <ul>
 *     <li>
 *         {@link Metadata} - use armeria's HttpHeaders and decorators for accessing custom metadata sent from
 *         the client. Any usages of {@link Metadata} in the server will be silently ignored.
 *     </li>
 * </ul>
 */
public final class GrpcService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(GrpcService.class);

    static final int NO_MAX_INBOUND_MESSAGE_SIZE = -1;

    private static final Metadata EMPTY_METADATA = new Metadata();

    private final HandlerRegistry registry;
    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;
    private final Set<SerializationFormat> supportedSerializationFormats;
    private final int maxOutboundMessageSizeBytes;

    private int maxInboundMessageSizeBytes;

    GrpcService(HandlerRegistry registry,
                DecompressorRegistry decompressorRegistry,
                CompressorRegistry compressorRegistry,
                Set<SerializationFormat> supportedSerializationFormats,
                int maxOutboundMessageSizeBytes,
                int maxInboundMessageSizeBytes) {
        this.registry = requireNonNull(registry, "registry");
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        this.supportedSerializationFormats = supportedSerializationFormats;
        this.maxOutboundMessageSizeBytes = maxOutboundMessageSizeBytes;
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) throws Exception {
        String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
        SerializationFormat serializationFormat = findSerializationFormat(contentType);
        if (serializationFormat == null) {
            res.respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Missing or invalid Content-Type header.");
            return;
        }

        ctx.logBuilder().serializationFormat(serializationFormat);

        String methodName = GrpcRequestUtil.determineMethod(ctx);
        if (methodName == null) {
            res.respond(HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Invalid path.");
            return;
        }

        ServerMethodDefinition<?, ?> method = registry.lookupMethod(methodName);
        if (method == null) {
            res.write(
                    ArmeriaServerCall.statusToTrailers(
                            Status.UNIMPLEMENTED.withDescription("Method not found: " + methodName),
                            false));
            res.close();
            return;
        }

        // We don't actually use the RpcRequest for request processing since it doesn't fit well with streaming.
        // We still populate it with a reasonable method name for use in logging. The service type is currently
        // arbitrarily set as Grpc doesn't use Class<?> to represent services - if this becomes a problem, we
        // would need to refactor it to take a Object instead.
        RpcRequest rpcRequest = RpcRequest.of(
                GrpcService.class, method.getMethodDescriptor().getFullMethodName());
        ctx.logBuilder().requestContent(rpcRequest, null);

        String timeoutHeader = req.headers().get(GrpcHeaderNames.GRPC_TIMEOUT);
        if (timeoutHeader != null) {
            try {
                long timeout = TimeoutHeaderUtil.fromHeaderValue(timeoutHeader);
                ctx.setRequestTimeout(Duration.ofNanos(timeout));
            } catch (IllegalArgumentException e) {
                res.write(ArmeriaServerCall.statusToTrailers(Status.fromThrowable(e), false));
                res.close();
            }
        }

        ArmeriaServerCall<?, ?> call = startCall(
                methodName, method, ctx, req.headers(), res, serializationFormat);
        req.subscribe(call.messageReader());
    }

    private <I, O> ArmeriaServerCall<I, O> startCall(
            String fullMethodName,
            ServerMethodDefinition<I, O> methodDef,
            ServiceRequestContext ctx,
            HttpHeaders headers,
            HttpResponseWriter res,
            SerializationFormat serializationFormat) {
        ArmeriaServerCall<I, O> call = new ArmeriaServerCall<>(
                headers,
                methodDef.getMethodDescriptor(),
                compressorRegistry,
                decompressorRegistry,
                res,
                maxInboundMessageSizeBytes,
                maxOutboundMessageSizeBytes,
                ctx,
                serializationFormat);
        ServerCall.Listener<I> listener = methodDef.getServerCallHandler().startCall(call, EMPTY_METADATA);
        if (listener == null) {
            throw new NullPointerException(
                    "startCall() returned a null listener for method " + fullMethodName);
        }
        call.setListener(listener);
        return call;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (maxInboundMessageSizeBytes == NO_MAX_INBOUND_MESSAGE_SIZE) {
            maxInboundMessageSizeBytes = (int) cfg.server().config().defaultMaxRequestLength();
        }
    }

    List<ServerServiceDefinition> services() {
        return registry.services();
    }

    @Nullable
    private SerializationFormat findSerializationFormat(@Nullable String contentType) {
        if (contentType == null) {
            return null;
        }

        final MediaType mediaType;
        try {
            mediaType = MediaType.parse(contentType);
        } catch (IllegalArgumentException e) {
            logger.debug("Failed to parse the 'content-type' header: {}", contentType, e);
            return null;
        }

        for (SerializationFormat format : supportedSerializationFormats) {
            if (format.isAccepted(mediaType)) {
                return format;
            }
        }

        return null;
    }
}
