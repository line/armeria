/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.grpc.protocol;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.common.grpc.protocol.Base64DecoderUtil.byteBufConverter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * An {@link AbstractUnsafeUnaryGrpcService} can be used to implement a gRPC service without depending on gRPC
 * stubs. This service takes care of deframing and framing with the gRPC wire format and handling appropriate
 * headers. This unsafe version of a unary gRPC service accepts and returns pooled {@link ByteBuf} for payloads.
 * It is for advanced users only, and it is generally recommended to use normal gRPC stubs or
 * {@link AbstractUnaryGrpcService}.
 *
 * <p>This service does not support compression. If you need support for compression, please consider using
 * normal gRPC stubs or file a feature request.
 */
@UnstableApi
public abstract class AbstractUnsafeUnaryGrpcService extends AbstractHttpService {

    private static final Set<SerializationFormat> SUPPORTED_SERIALIZATION_FORMATS =
            UnaryGrpcSerializationFormats.values();
    private static final Map<SerializationFormat, ResponseHeaders> RESPONSE_HEADERS_MAP =
            SUPPORTED_SERIALIZATION_FORMATS
                    .stream()
                    .collect(toImmutableMap(f -> f, f -> ResponseHeaders
                            .builder(HttpStatus.OK)
                            .contentType(f.mediaType())
                            .add(GrpcHeaderNames.GRPC_ENCODING, "identity")
                            .build()
                    ));

    /**
     * Returns an unframed response message to return to the client, given an unframed request message. It is
     * expected that the implementation has the logic to know how to parse the request and serialize a response
     * into {@link ByteBuf}. The returned {@link ByteBuf} will be framed and returned to the client.
     */
    protected abstract CompletionStage<ByteBuf> handleMessage(ServiceRequestContext ctx, ByteBuf message);

    @Nullable
    private static SerializationFormat resolveSerializationFormat(HttpRequest req) {
        @Nullable final MediaType contentType = req.contentType();
        if (contentType == null) {
            return null;
        }
        for (SerializationFormat format : SUPPORTED_SERIALIZATION_FORMATS) {
            if (format.isAccepted(contentType)) {
                return format;
            }
        }
        return null;
    }

    @Override
    protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<ByteBuf> deframed = new CompletableFuture<>();
        final ArmeriaMessageDeframer deframer = new ArmeriaMessageDeframer(Integer.MAX_VALUE);
        @Nullable final SerializationFormat serializationFormat = resolveSerializationFormat(req);
        if (serializationFormat == null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Missing or invalid Content-Type header.");
        }
        final boolean isGrpcWebText = UnaryGrpcSerializationFormats.isGrpcWebText(serializationFormat);
        req.decode(deframer, ctx.alloc(), byteBufConverter(ctx.alloc(), isGrpcWebText))
           .subscribe(singleSubscriber(deframed), ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);

        final CompletableFuture<HttpResponse> responseFuture =
                deframed.thenCompose(requestMessage -> {
                    try (SafeCloseable ignored = ctx.push()) {
                        return handleMessage(ctx, requestMessage);
                    }
                }).thenApply(responseMessage -> {
                    final HttpHeadersBuilder trailersBuilder = HttpHeaders.builder();
                    GrpcTrailersUtil.addStatusMessageToTrailers(trailersBuilder, StatusCodes.OK, null);
                    final HttpHeaders trailers = trailersBuilder.build();
                    GrpcWebTrailers.set(ctx, trailers);
                    final ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                            ctx.alloc(), Integer.MAX_VALUE, isGrpcWebText);
                    final HttpData content = framer.writePayload(responseMessage);
                    final ResponseHeaders responseHeaders = RESPONSE_HEADERS_MAP.get(serializationFormat);
                    if (UnaryGrpcSerializationFormats.isGrpcWeb(serializationFormat)) {
                        // Send trailer as a part of the body for gRPC-web.
                        final HttpData serializedTrailers = framer.writePayload(
                                GrpcTrailersUtil.serializeTrailersAsMessage(ctx.alloc(), trailers), true);
                        return HttpResponse.of(responseHeaders, content, serializedTrailers);
                    }
                    return HttpResponse.of(responseHeaders, content, trailers);
                }).exceptionally(t -> {
                    // Send Trailers-Only → HTTP-Status Content-Type Trailers.
                    final ResponseHeadersBuilder trailersBuilder = ResponseHeaders
                            .builder(HttpStatus.OK).contentType(serializationFormat.mediaType());
                    if (t instanceof ArmeriaStatusException) {
                        final ArmeriaStatusException statusException = (ArmeriaStatusException) t;
                        GrpcTrailersUtil.addStatusMessageToTrailers(
                                trailersBuilder, statusException.getCode(), statusException.getMessage());
                    } else {
                        GrpcTrailersUtil.addStatusMessageToTrailers(
                                trailersBuilder, StatusCodes.INTERNAL, t.getMessage());
                    }
                    final ResponseHeaders trailers = trailersBuilder.build();
                    GrpcWebTrailers.set(ctx, trailers);
                    return HttpResponse.of(trailers);
                });

        return HttpResponse.from(responseFuture);
    }

    private static Subscriber<DeframedMessage> singleSubscriber(CompletableFuture<ByteBuf> deframed) {
        return new Subscriber<DeframedMessage>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(DeframedMessage message) {
                final ByteBuf buf = message.buf();
                assert buf != null;
                deframed.complete(buf);
            }

            @Override
            public void onError(Throwable t) {
                deframed.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                if (!deframed.isDone()) {
                    deframed.complete(Unpooled.EMPTY_BUFFER);
                }
            }
        };
    }
}
