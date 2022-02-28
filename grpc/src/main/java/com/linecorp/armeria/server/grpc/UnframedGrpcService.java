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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.encoding.EncodingService;

import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;

/**
 * A {@link SimpleDecoratingHttpService} which allows {@link GrpcService} to serve requests without the framing
 * specified by the gRPC wire protocol. This can be useful for serving both legacy systems and gRPC clients with
 * the same business logic.
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
final class UnframedGrpcService extends AbstractUnframedGrpcService {

    private final Map<String, ServerMethodDefinition<?, ?>> methodsByName;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    UnframedGrpcService(GrpcService delegate, HandlerRegistry registry,
                        UnframedGrpcErrorHandler unframedGrpcErrorHandler) {
        super(delegate, unframedGrpcErrorHandler);
        checkArgument(delegate.isFramed(), "Decorated service must be a framed GrpcService.");
        methodsByName = registry.methods();
    }

    @Override
    public Map<String, ServerMethodDefinition<?, ?>> methods() {
        return methodsByName;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final RequestHeaders clientHeaders = req.headers();
        final MediaType contentType = clientHeaders.contentType();
        if (contentType == null) {
            // All gRPC requests, whether framed or non-framed, must have content-type. If it's not sent, let
            // the delegate return its usual error message.
            return unwrap().serve(ctx, req);
        }

        for (SerializationFormat format : GrpcSerializationFormats.values()) {
            if (format.isAccepted(contentType)) {
                // Framed request, so just delegate.
                return unwrap().serve(ctx, req);
            }
        }

        final String methodName = GrpcRequestUtil.determineMethod(ctx);
        final ServerMethodDefinition<?, ?> method;
        if (methodName != null) {
            method = methodsByName.get(methodName);
        } else {
            method = null;
        }

        if (method == null) {
            // Unknown method, let the delegate return a usual error.
            return unwrap().serve(ctx, req);
        }

        if (method.getMethodDescriptor().getType() != MethodType.UNARY) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Only unary methods can be used with non-framed requests.");
        }

        final RequestHeadersBuilder grpcHeaders = clientHeaders.toBuilder();

        final MediaType framedContentType;
        if (contentType.is(MediaType.PROTOBUF)) {
            framedContentType = GrpcSerializationFormats.PROTO.mediaType();
        } else if (contentType.isJson()) {
            framedContentType = GrpcSerializationFormats.JSON.mediaType();
        } else {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Unsupported media type. Only application/protobuf is supported.");
        }
        grpcHeaders.contentType(framedContentType);

        if (grpcHeaders.get(GrpcHeaderNames.GRPC_ENCODING) != null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "gRPC encoding is not supported for non-framed requests.");
        }

        // All clients support no encoding, and we don't support gRPC encoding for non-framed requests, so just
        // clear the header if it's present.
        grpcHeaders.remove(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);

        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT,
                               RequestLogProperty.RESPONSE_CONTENT);

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((clientRequest, t) -> {
            try (SafeCloseable ignore = ctx.push()) {
                if (t != null) {
                    responseFuture.completeExceptionally(t);
                } else {
                    ctx.setAttr(FramedGrpcService.RESOLVED_GRPC_METHOD, method);
                    frameAndServe(unwrap(), ctx, grpcHeaders.build(), clientRequest.content(), responseFuture);
                }
            }
            return null;
        });
        return HttpResponse.from(responseFuture);
    }
}
