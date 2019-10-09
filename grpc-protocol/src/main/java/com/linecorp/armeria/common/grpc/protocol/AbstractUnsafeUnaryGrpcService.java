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

package com.linecorp.armeria.common.grpc.protocol;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.Listener;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
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
                req.aggregateWithPooledObjects(ctx.contextAwareEventLoop(), ctx.alloc())
                   .thenCompose(msg -> deframeMessage(msg.content(), ctx.alloc()))
                   .thenCompose(this::handleMessage)
                   .thenApply(responseMessage -> {
                       final ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                               ctx.alloc(), Integer.MAX_VALUE);
                       final HttpData framed = framer.writePayload(responseMessage);
                       return HttpResponse.of(
                               RESPONSE_HEADERS,
                               framed,
                               GrpcTrailersUtil.statusToTrailers(StatusCodes.OK, null, true).build());
                   })
                   .exceptionally(t -> {
                       final HttpHeadersBuilder trailers;
                       if (t instanceof ArmeriaStatusException) {
                           final ArmeriaStatusException statusException = (ArmeriaStatusException) t;
                           trailers = GrpcTrailersUtil.statusToTrailers(
                                   statusException.getCode(), statusException.getMessage(), false);
                       } else {
                           trailers = GrpcTrailersUtil.statusToTrailers(
                                   StatusCodes.INTERNAL, t.getMessage(), false);
                       }
                       return HttpResponse.of(trailers.build());
                   });

        return HttpResponse.from(responseFuture);
    }

    private CompletableFuture<ByteBuf> deframeMessage(HttpData framed, ByteBufAllocator alloc) {
        final CompletableFuture<ByteBuf> deframed = new CompletableFuture<>();
        try (ArmeriaMessageDeframer deframer = new ArmeriaMessageDeframer(
                new Listener() {
                    @Override
                    public void messageRead(DeframedMessage message) {
                        // Compression not supported.
                        assert message.buf() != null;
                        deframed.complete(message.buf());
                    }

                    @Override
                    public void endOfStream() {
                        if (!deframed.isDone()) {
                            deframed.complete(Unpooled.EMPTY_BUFFER);
                        }
                    }
                },
                Integer.MAX_VALUE,
                alloc)) {
            deframer.request(1);
            deframer.deframe(framed, true);
        }
        return deframed;
    }
}
