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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.Listener;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcStatus;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceWithPathMappings;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

/**
 * A {@link SimpleDecoratingService} which allows {@link GrpcService} to serve requests without the framing
 * specified by the gRPC wire protocol. This can be useful for serving both legacy systems and gRPC clients with
 * the same business logic.
 *
 * <p>Limitations:
 * <ul>
 *     <li>Only unary methods (single request, single response) are supported.</li>
 *     <li>
 *         Message compression is not supported.
 *         {@link HttpEncodingService} should be used instead for
 *         transport level encoding.
 *     </li>
 * </ul>
 */
class UnframedGrpcService extends SimpleDecoratingService<HttpRequest, HttpResponse>
        implements ServiceWithPathMappings<HttpRequest, HttpResponse> {

    private static final char LINE_SEPARATOR = '\n';

    private final Map<String, MethodDescriptor<?, ?>> methodsByName;
    private final GrpcService delegateGrpcService;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    UnframedGrpcService(Service<HttpRequest, HttpResponse> delegate) {
        super(delegate);
        delegateGrpcService =
                delegate.as(GrpcService.class)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Decorated service must be a GrpcService."));
        methodsByName = delegateGrpcService.services()
                                   .stream()
                                   .flatMap(service -> service.getMethods().stream())
                                   .map(ServerMethodDefinition::getMethodDescriptor)
                                   .collect(ImmutableMap.toImmutableMap(MethodDescriptor::getFullMethodName,
                                                                        Function.identity()));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpHeaders clientHeaders = req.headers();
        final MediaType contentType = clientHeaders.contentType();
        if (contentType == null) {
            // All gRPC requests, whether framed or non-framed, must have content-type. If it's not sent, let
            // the delegate return its usual error message.
            return delegate().serve(ctx, req);
        }

        for (SerializationFormat format : GrpcSerializationFormats.values()) {
            if (format.isAccepted(contentType)) {
                // Framed request, so just delegate.
                return delegate().serve(ctx, req);
            }
        }

        final String methodName = GrpcRequestUtil.determineMethod(ctx);
        final MethodDescriptor<?, ?> method = methodName != null ? methodsByName.get(methodName) : null;
        if (method == null) {
            // Unknown method, let the delegate return a usual error.
            return delegate().serve(ctx, req);
        }

        if (method.getType() != MethodType.UNARY) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Only unary methods can be used with non-framed requests.");
        }

        final HttpHeaders grpcHeaders = HttpHeaders.copyOf(clientHeaders);

        final MediaType framedContentType;
        if (contentType.is(MediaType.PROTOBUF)) {
            framedContentType = GrpcSerializationFormats.PROTO.mediaType();
        } else if (contentType.is(MediaType.JSON_UTF_8)) {
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

        ctx.logBuilder().deferRequestContent();
        ctx.logBuilder().deferResponseContent();

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((clientRequest, t) -> {
            if (t != null) {
                responseFuture.completeExceptionally(t);
            } else {
                frameAndServe(ctx, grpcHeaders, clientRequest, responseFuture);
            }
            return null;
        });
        return HttpResponse.from(responseFuture);
    }

    private void frameAndServe(
            ServiceRequestContext ctx,
            HttpHeaders grpcHeaders,
            AggregatedHttpMessage clientRequest,
            CompletableFuture<HttpResponse> res) {
        final HttpRequest grpcRequest;
        try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                ctx.alloc(), ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE)) {
            final HttpData content = clientRequest.content();
            final ByteBuf message;
            if (content instanceof ByteBufHolder) {
                message = ((ByteBufHolder) content).content();
            } else {
                message = ctx.alloc().buffer(content.length());
                message.writeBytes(content.array(), content.offset(), content.length());
            }
            final HttpData frame;
            boolean success = false;
            try {
                frame = framer.writePayload(message);
                success = true;
            } finally {
                if (!success) {
                    message.release();
                }
            }
            grpcRequest = HttpRequest.of(grpcHeaders, frame);
        }

        final HttpResponse grpcResponse;
        try {
            grpcResponse = delegate().serve(ctx, grpcRequest);
        } catch (Exception e) {
            res.completeExceptionally(e);
            return;
        }

        grpcResponse.aggregate().handleAsync(
                (framedResponse, t) -> {
                    if (t != null) {
                        res.completeExceptionally(t);
                    } else {
                        deframeAndRespond(ctx, framedResponse, res);
                    }
                    return null;
                },
                ctx.eventLoop());
    }

    private static void deframeAndRespond(
            ServiceRequestContext ctx,
            AggregatedHttpMessage grpcResponse,
            CompletableFuture<HttpResponse> res) {
        final HttpHeaders trailers = !grpcResponse.trailingHeaders().isEmpty() ?
                                     grpcResponse.trailingHeaders() : grpcResponse.headers();
        final String grpcStatusCode = trailers.get(GrpcHeaderNames.GRPC_STATUS);
        final Status grpcStatus = Status.fromCodeValue(Integer.parseInt(grpcStatusCode));

        if (grpcStatus.getCode() != Status.OK.getCode()) {
            final HttpStatus httpStatus = GrpcStatus.grpcCodeToHttpStatus(grpcStatus.getCode());
            final StringBuilder message = new StringBuilder("http-status: " + httpStatus.code());
            message.append(", ").append(httpStatus.reasonPhrase()).append(LINE_SEPARATOR);
            message.append("Caused by: ").append(LINE_SEPARATOR);
            message.append("grpc-status: ")
                   .append(grpcStatusCode)
                   .append(", ")
                   .append(grpcStatus.getCode().name());
            final String grpcMessage = trailers.get(GrpcHeaderNames.GRPC_MESSAGE);
            if (grpcMessage != null) {
                message.append(", ").append(grpcMessage);
            }

            res.complete(HttpResponse.of(
                    httpStatus,
                    MediaType.PLAIN_TEXT_UTF_8,
                    message.toString()));
            return;
        }

        final MediaType grpcMediaType = grpcResponse.contentType();
        final HttpHeaders unframedHeaders = HttpHeaders.copyOf(grpcResponse.headers());
        if (grpcMediaType != null) {
            if (grpcMediaType.is(GrpcSerializationFormats.PROTO.mediaType())) {
                unframedHeaders.contentType(MediaType.PROTOBUF);
            } else if (grpcMediaType.is(GrpcSerializationFormats.JSON.mediaType())) {
                unframedHeaders.contentType(MediaType.JSON_UTF_8);
            }
        }

        try (ArmeriaMessageDeframer deframer = new ArmeriaMessageDeframer(
                new Listener() {
                    @Override
                    public void messageRead(ByteBufOrStream message) {
                        // We know there is only one message in total, so don't bother with checking endOfStream
                        // We also know that we don't support compression, so this is always a ByteBuffer.
                        final HttpData unframedContent = new ByteBufHttpData(message.buf(), true);
                        unframedHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, unframedContent.length());
                        res.complete(HttpResponse.of(unframedHeaders, unframedContent));
                    }

                    @Override
                    public void endOfStream() {}
                },
                // Max outbound message size is handled by the GrpcService, so we don't need to set it here.
                Integer.MAX_VALUE,
                ctx.alloc())) {
            deframer.request(1);
            deframer.deframe(grpcResponse.content(), true);
        }
    }

    @Override
    public Set<PathMapping> pathMappings() {
        return delegateGrpcService.pathMappings();
    }
}
