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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.encoding.EncodingService;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;

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
final class UnframedGrpcService extends SimpleDecoratingHttpService implements GrpcService {

    private static final char LINE_SEPARATOR = '\n';

    private final Map<String, ServerMethodDefinition<?, ?>> methodsByName;
    private final GrpcService delegateGrpcService;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    UnframedGrpcService(GrpcService delegate, HandlerRegistry registry) {
        super(delegate);
        checkArgument(delegate.isFramed(), "Decorated service must be a framed GrpcService.");
        delegateGrpcService = delegate;
        methodsByName = registry.methods();
    }

    @Override
    public boolean isFramed() {
        return false;
    }

    @Override
    public List<ServerServiceDefinition> services() {
        return delegateGrpcService.services();
    }

    @Override
    public Map<String, ServerMethodDefinition<?, ?>> methods() {
        return methodsByName;
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return delegateGrpcService.supportedSerializationFormats();
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
        final MethodDescriptor<?, ?> method;
        if (methodName != null) {
            final ServerMethodDefinition<?, ?> methodDef = methodsByName.get(methodName);
            if (methodDef != null) {
                method = methodDef.getMethodDescriptor();
            } else {
                method = null;
            }
        } else {
            method = null;
        }

        if (method == null) {
            // Unknown method, let the delegate return a usual error.
            return unwrap().serve(ctx, req);
        }

        if (method.getType() != MethodType.UNARY) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Only unary methods can be used with non-framed requests.");
        }

        final RequestHeadersBuilder grpcHeaders = clientHeaders.toBuilder();

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

        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT,
                               RequestLogProperty.RESPONSE_CONTENT);

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((clientRequest, t) -> {
            if (t != null) {
                responseFuture.completeExceptionally(t);
            } else {
                frameAndServe(ctx, grpcHeaders.build(), clientRequest, responseFuture);
            }
            return null;
        });
        return HttpResponse.from(responseFuture);
    }

    private void frameAndServe(
            ServiceRequestContext ctx,
            RequestHeaders grpcHeaders,
            AggregatedHttpRequest clientRequest,
            CompletableFuture<HttpResponse> res) {
        final HttpRequest grpcRequest;
        try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                ctx.alloc(), ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE, false)) {
            final HttpData content = clientRequest.content();
            final HttpData frame;
            boolean success = false;
            try {
                frame = framer.writePayload(content.byteBuf());
                success = true;
            } finally {
                if (!success) {
                    content.close();
                }
            }
            grpcRequest = HttpRequest.of(grpcHeaders, frame);
        }

        final HttpResponse grpcResponse;
        try {
            grpcResponse = unwrap().serve(ctx, grpcRequest);
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
            AggregatedHttpResponse grpcResponse,
            CompletableFuture<HttpResponse> res) {
        final HttpHeaders trailers = !grpcResponse.trailers().isEmpty() ?
                                     grpcResponse.trailers() : grpcResponse.headers();
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

            final ResponseHeaders headers = ResponseHeaders.builder(httpStatus)
                                                           .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                                           .add(GrpcHeaderNames.GRPC_STATUS, grpcStatusCode)
                                                           .build();
            res.complete(HttpResponse.of(headers, HttpData.ofUtf8(message.toString())));
            return;
        }

        final MediaType grpcMediaType = grpcResponse.contentType();
        final ResponseHeadersBuilder unframedHeaders = grpcResponse.headers().toBuilder();
        unframedHeaders.set(GrpcHeaderNames.GRPC_STATUS, grpcStatusCode); // grpcStatusCode is 0 which is OK.
        if (grpcMediaType != null) {
            if (grpcMediaType.is(GrpcSerializationFormats.PROTO.mediaType())) {
                unframedHeaders.contentType(MediaType.PROTOBUF);
            } else if (grpcMediaType.is(GrpcSerializationFormats.JSON.mediaType())) {
                unframedHeaders.contentType(MediaType.JSON_UTF_8);
            }
        }

        final ArmeriaMessageDeframerHandler handler = new ArmeriaMessageDeframerHandler(
                // Max outbound message size is handled by the GrpcService, so we don't need to set it here.
                Integer.MAX_VALUE);
        final HttpDeframer<DeframedMessage> deframer = new HttpDeframer<>(handler, ctx.alloc());
        StreamMessage.of(grpcResponse.content()).subscribe(deframer, ctx.eventLoop());
        deframer.subscribe(singleSubscriber(unframedHeaders, res), ctx.eventLoop());
    }

    private static Subscriber<DeframedMessage> singleSubscriber(ResponseHeadersBuilder unframedHeaders,
                                                                CompletableFuture<HttpResponse> res) {
        return new Subscriber<DeframedMessage>() {

            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(DeframedMessage message) {
                // We know that we don't support compression, so this is always a ByteBuffer.
                final HttpData unframedContent = HttpData.wrap(message.buf()).withEndOfStream();
                unframedHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, unframedContent.length());
                res.complete(HttpResponse.of(unframedHeaders.build(), unframedContent));
            }

            @Override
            public void onError(Throwable t) {
                if (!res.isDone()) {
                    res.completeExceptionally(t);
                }
            }

            @Override
            public void onComplete() {
                if (!res.isDone()) {
                    // If 'ResponseObserver.onCompleted()' is called without calling 'onNext()',
                    // this callback would be invoked but 'messageRead' callback wouldn't.
                    res.complete(HttpResponse.of(unframedHeaders.build()));
                }
            }
        };
    }

    @Override
    public Set<Route> routes() {
        return delegateGrpcService.routes();
    }
}
