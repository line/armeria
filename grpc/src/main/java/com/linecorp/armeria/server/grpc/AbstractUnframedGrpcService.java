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

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Common part of the {@link UnframedGrpcService} and {@link HttpJsonTranscodingService}.
 */
abstract class AbstractUnframedGrpcService extends SimpleDecoratingHttpService implements GrpcService {

    private final GrpcService delegate;
    private final UnframedGrpcErrorHandler unframedGrpcErrorHandler;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    AbstractUnframedGrpcService(GrpcService delegate, UnframedGrpcErrorHandler unframedGrpcErrorHandler) {
        super(delegate);
        this.delegate = delegate;
        this.unframedGrpcErrorHandler = requireNonNull(unframedGrpcErrorHandler, "unframedGrpcErrorHandler");
    }

    @Override
    public Set<Route> routes() {
        return delegate.routes();
    }

    @Override
    public ExchangeType exchangeType(RequestHeaders headers, Route route) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return ExchangeType.BIDI_STREAMING;
        }

        for (SerializationFormat format : GrpcSerializationFormats.values()) {
            if (format.isAccepted(contentType)) {
                return ((HttpService) unwrap()).exchangeType(headers, route);
            }
        }

        if (contentType.is(MediaType.PROTOBUF) || contentType.is(MediaType.JSON_UTF_8)) {
            return ExchangeType.UNARY;
        }
        // Unsupported Content-Type
        return ExchangeType.BIDI_STREAMING;
    }

    @Override
    public boolean isFramed() {
        return false;
    }

    @Override
    public Map<String, ServerMethodDefinition<?, ?>> methods() {
        return delegate.methods();
    }

    @Override
    public List<ServerServiceDefinition> services() {
        return delegate.services();
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return delegate.supportedSerializationFormats();
    }

    protected void frameAndServe(
            Service<HttpRequest, HttpResponse> delegate,
            ServiceRequestContext ctx,
            RequestHeaders grpcHeaders,
            HttpData content,
            CompletableFuture<HttpResponse> res) {
        final HttpRequest grpcRequest;
        try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                ctx.alloc(), ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE, false)) {
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
            grpcResponse = delegate.serve(ctx, grpcRequest);
        } catch (Exception e) {
            res.completeExceptionally(e);
            return;
        }

        grpcResponse.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle(
                (framedResponse, t) -> {
                    try (SafeCloseable ignore = ctx.push()) {
                        if (t != null) {
                            res.completeExceptionally(t);
                        } else {
                            deframeAndRespond(ctx, framedResponse, res, unframedGrpcErrorHandler);
                        }
                    }
                    return null;
                });
    }

    @VisibleForTesting
    static void deframeAndRespond(ServiceRequestContext ctx,
                                  AggregatedHttpResponse grpcResponse,
                                  CompletableFuture<HttpResponse> res,
                                  UnframedGrpcErrorHandler unframedGrpcErrorHandler) {
        final HttpHeaders trailers = !grpcResponse.trailers().isEmpty() ?
                                     grpcResponse.trailers() : grpcResponse.headers();
        final String grpcStatusCode = trailers.get(GrpcHeaderNames.GRPC_STATUS);
        Status grpcStatus = Status.fromCodeValue(Integer.parseInt(grpcStatusCode));
        final String grpcMessage = trailers.get(GrpcHeaderNames.GRPC_MESSAGE);
        if (!Strings.isNullOrEmpty(grpcMessage)) {
            grpcStatus = grpcStatus.withDescription(grpcMessage);
        }

        if (grpcStatus.getCode() != Code.OK) {
            PooledObjects.close(grpcResponse.content());
            try {
                res.complete(unframedGrpcErrorHandler.handle(ctx, grpcStatus, grpcResponse));
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
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

        final ArmeriaMessageDeframer deframer = new ArmeriaMessageDeframer(
                // Max outbound message size is handled by the GrpcService, so we don't need to set it here.
                Integer.MAX_VALUE);
        grpcResponse.toHttpResponse().decode(deframer, ctx.alloc())
                    .subscribe(singleSubscriber(unframedHeaders, res), ctx.eventLoop(),
                               SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    static Subscriber<DeframedMessage> singleSubscriber(ResponseHeadersBuilder unframedHeaders,
                                                        CompletableFuture<HttpResponse> res) {
        return new Subscriber<DeframedMessage>() {

            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(DeframedMessage message) {
                // We know that we don't support compression, so this is always a ByteBuf.
                final HttpData unframedContent = HttpData.wrap(message.buf()).withEndOfStream();
                unframedHeaders.contentLength(unframedContent.length());
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
}
