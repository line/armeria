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

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;

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

    private static final ResponseHeaders RESPONSE_HEADERS =
            ResponseHeaders.of(HttpStatus.OK,
                               HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto",
                               GrpcHeaderNames.GRPC_ENCODING, "identity");

    /**
     * Returns an unframed response message to return to the client, given an unframed request message. It is
     * expected that the implementation has the logic to know how to parse the request and serialize a response
     * into {@link ByteBuf}. The returned {@link ByteBuf} will be framed and returned to the client.
     */
    protected abstract CompletableFuture<ByteBuf> handleMessage(ByteBuf message);

    @Override
    protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> responseFuture =
                req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc())
                   .thenCompose(msg -> deframeMessage(msg.content(), ctx.eventLoop(), ctx.alloc()))
                   .thenCompose(this::handleMessage)
                   .thenApply(responseMessage -> {
                       final ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                               ctx.alloc(), Integer.MAX_VALUE, false);
                       final HttpData framed = framer.writePayload(responseMessage);
                       final HttpHeadersBuilder trailers = HttpHeaders.builder();
                       GrpcTrailersUtil.addStatusMessageToTrailers(trailers, StatusCodes.OK, null);
                       return HttpResponse.of(
                               RESPONSE_HEADERS,
                               framed,
                               trailers.build());
                   })
                   .exceptionally(t -> {
                       final HttpHeadersBuilder trailers = RESPONSE_HEADERS.toBuilder();
                       if (t instanceof ArmeriaStatusException) {
                           final ArmeriaStatusException statusException = (ArmeriaStatusException) t;
                           GrpcTrailersUtil.addStatusMessageToTrailers(
                                   trailers, statusException.getCode(), statusException.getMessage());
                       } else {
                           GrpcTrailersUtil.addStatusMessageToTrailers(
                                   trailers, StatusCodes.INTERNAL, t.getMessage());
                       }
                       return HttpResponse.of(trailers.build());
                   });

        return HttpResponse.from(responseFuture);
    }

    private static CompletableFuture<ByteBuf> deframeMessage(HttpData framed,
                                                             EventLoop eventLoop,
                                                             ByteBufAllocator alloc) {
        final CompletableFuture<ByteBuf> deframed = new CompletableFuture<>();
        final ArmeriaMessageDeframerHandler handler = new ArmeriaMessageDeframerHandler(Integer.MAX_VALUE);
        final HttpDeframer<DeframedMessage> deframer = new HttpDeframer<>(handler, alloc);

        StreamMessage.of(framed).subscribe(deframer, eventLoop);
        deframer.subscribe(singleSubscriber(deframed), eventLoop);
        return deframed;
    }

    private static Subscriber<DeframedMessage> singleSubscriber(CompletableFuture<ByteBuf> deframed) {
        return new Subscriber<DeframedMessage>()  {
            @Override
            public void onSubscribe(Subscription s) {
               s.request(1);
            }

            @Override
            public void onNext(DeframedMessage message) {
                deframed.complete(message.buf());
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
