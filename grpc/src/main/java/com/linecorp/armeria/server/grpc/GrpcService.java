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

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.MediaType;
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
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.http2.Http2Exception;

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

    static final int NO_MAX_INBOUND_MESSAGE_SIZE = -1;

    @VisibleForTesting
    static final String CONTENT_TYPE_GRPC = "application/grpc";

    private static final Metadata EMPTY_METADATA = new Metadata();

    private final HandlerRegistry registry;
    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;
    private final int maxOutboundMessageSizeBytes;

    private int maxInboundMessageSizeBytes;

    GrpcService(HandlerRegistry registry,
                DecompressorRegistry decompressorRegistry,
                CompressorRegistry compressorRegistry,
                int maxOutboundMessageSizeBytes,
                int maxInboundMessageSizeBytes) {
        this.registry = requireNonNull(registry, "registry");
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        this.maxOutboundMessageSizeBytes = maxOutboundMessageSizeBytes;
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) throws Exception {
        if (!verifyContentType(req.headers())) {
            res.respond(HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Missing or invalid Content-Type header.");
            return;
        }
        String methodName = determineMethod(ctx);
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

        ArmeriaServerCall<?, ?> call = startCall(methodName, method, ctx, req.headers(), res);
        req.subscribe(call.messageReader());
    }

    private <I, O> ArmeriaServerCall<I, O> startCall(
            String fullMethodName,
            ServerMethodDefinition<I, O> methodDef,
            ServiceRequestContext ctx,
            HttpHeaders headers,
            HttpResponseWriter res) {
        ArmeriaServerCall<I, O> call = new ArmeriaServerCall<>(
                headers,
                methodDef.getMethodDescriptor(),
                compressorRegistry,
                decompressorRegistry,
                UnpooledByteBufAllocator.DEFAULT,
                res,
                maxInboundMessageSizeBytes,
                maxOutboundMessageSizeBytes,
                ctx);
        ServerCall.Listener<I> listener = methodDef.getServerCallHandler().startCall(call, EMPTY_METADATA);
        if (listener == null) {
            throw new NullPointerException(
                    "startCall() returned a null listener for method " + fullMethodName);
        }
        call.setListener(listener);
        return call;
    }

    private boolean verifyContentType(HttpHeaders headers) throws Http2Exception {
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        return contentType != null && isGrpcContentType(contentType);
    }

    @Nullable
    private String determineMethod(ServiceRequestContext ctx) throws Http2Exception {
        // Remove the leading slash of the path and get the fully qualified method name
        String path = ctx.mappedPath();
        if (path.charAt(0) != '/') {
            return null;
        }
        return path.substring(1, path.length());
    }

    private static boolean isGrpcContentType(String contentType) {
        if (CONTENT_TYPE_GRPC.length() > contentType.length()) {
            return false;
        }

        contentType = contentType.toLowerCase();
        if (!contentType.startsWith(CONTENT_TYPE_GRPC)) {
            // Not a gRPC content-type.
            return false;
        }

        if (contentType.length() == CONTENT_TYPE_GRPC.length()) {
            // The strings match exactly.
            return true;
        }

        // The contentType matches, but is longer than the expected string.
        // We need to support variations on the content-type (e.g. +proto, +json) as defined by the
        // gRPC wire spec.
        char nextChar = contentType.charAt(CONTENT_TYPE_GRPC.length());
        return nextChar == '+' || nextChar == ';';
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
}
