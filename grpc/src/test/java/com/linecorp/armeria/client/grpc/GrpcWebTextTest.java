/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;

class GrpcWebTextTest {

    static final String PAYLOAD = "abcdefghijklmnopqrstuvwxyz";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/armeria.grpc.testing.TestService/UnaryCall", new TestService());
        }
    };

    @Test
    void unaryCallSuccessWhenEncodedDataSpansMultipleHttpFrames() {
        final TestServiceBlockingStub stub =
                Clients.newClient(server.httpUri(GrpcSerializationFormats.PROTO_WEB_TEXT),
                                  TestServiceBlockingStub.class);
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(PAYLOAD))
                                                .build())
                             .build();
        assertThat(stub.unaryCall(request).getPayload().getBody().toStringUtf8())
                .isEqualTo(PAYLOAD);
    }

    private static final class TestService extends AbstractHttpService {

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            final CompletableFuture<HttpResponse> responseFuture =
                    req.aggregate()
                       .thenCompose(msg -> deframeMessage(msg.content(), ctx.eventLoop(), ctx.alloc()))
                       .thenCompose(TestService::handleMessage)
                       .thenApply(responseMessage -> {
                           final HttpResponseWriter streaming = HttpResponse.streaming();
                           streaming.write(ResponseHeaders.of(
                                   HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, "application/grpc-web-text+proto",
                                   GrpcHeaderNames.GRPC_ENCODING, "identity"));
                           writeEncodedMessageAcrossFrames(responseMessage, streaming);
                           writeTrailers(ctx, streaming);
                           return streaming;
                       });

            return HttpResponse.from(responseFuture);
        }

        private static void writeEncodedMessageAcrossFrames(
                ByteBuf responseMessage, HttpResponseWriter streaming) {
            final ByteBuf buf = serializeMessage(responseMessage, false);
            assert buf.readableBytes() == 35;
            final ByteBuf first = encode64(buf.slice(0, 19));
            final ByteBuf second = encode64(buf.slice(19, 14));
            final ByteBuf third = encode64(buf.slice(33, 2));
            assert first.readableBytes() == 28; // == appended.
            assert second.readableBytes() == 20; // = appended.
            assert third.readableBytes() == 4; // = appended.

            // First HTTP data frame. (AAAAAB4KHBIaYW)
            streaming.write(HttpData.wrap(first.retainedSlice(0, 14)));
            // Second HTTP data frame. (JjZGVmZ2hpag==a2xtbm9w)
            // This includes the padding (==) in the middle of the frame.
            streaming.write(HttpData.wrap(
                    Unpooled.wrappedBuffer(first.retainedSlice(14, 28 - 14), second.retainedSlice(0, 8))));
            // Third HTTP data frame. (cXJzdHV2d3g=eXo=)
            // This includes two separate padding.
            streaming.write(HttpData.wrap(
                    Unpooled.wrappedBuffer(second.retainedSlice(8, 20 - 8), third)));
            first.release();
            second.release();
            buf.release();
        }

        private static void writeTrailers(ServiceRequestContext ctx, HttpResponseWriter streaming) {
            final HttpHeadersBuilder trailersBuilder = HttpHeaders.builder();
            GrpcTrailersUtil.addStatusMessageToTrailers(trailersBuilder, StatusCodes.OK, null);
            final ByteBuf serializedTrailers =
                    GrpcTrailersUtil.serializeTrailersAsMessage(ctx.alloc(), trailersBuilder.build());
            final HttpData httpdataTrailers = HttpData.wrap(
                    encode64(serializeMessage(serializedTrailers, true))).withEndOfStream();
            streaming.write(httpdataTrailers);
        }

        private static ByteBuf encode64(ByteBuf buf) {
            final ByteBuffer encoded = Base64.getEncoder().encode(buf.nioBuffer());
            return Unpooled.wrappedBuffer(encoded);
        }

        private static ByteBuf serializeMessage(ByteBuf message, boolean trailers) {
            final int messageLength = message.readableBytes();
            final ByteBuf buf = Unpooled.buffer(5 /* header length */ + messageLength);
            buf.writeByte(trailers ? (byte) (1 << 7) : (byte) 0);
            buf.writeInt(messageLength);
            buf.writeBytes(message);
            message.release();
            return buf;
        }

        private static CompletableFuture<ByteBuf> deframeMessage(HttpData framed,
                                                                 EventLoop eventLoop,
                                                                 ByteBufAllocator alloc) {
            final CompletableFuture<ByteBuf> deframed = new CompletableFuture<>();
            final HttpDeframer<DeframedMessage> deframer =
                    new ArmeriaMessageDeframer(Integer.MAX_VALUE).newHttpDeframer(alloc, true);
            StreamMessage.of(framed).subscribe(deframer, eventLoop);
            deframer.subscribe(singleSubscriber(deframed), eventLoop);
            return deframed;
        }

        private static Subscriber<DeframedMessage> singleSubscriber(CompletableFuture<ByteBuf> deframed) {
            return new Subscriber<DeframedMessage>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(1);
                }

                @Override
                public void onNext(DeframedMessage message) {
                    // Compression not supported.
                    assert message.buf() != null;
                    deframed.complete(message.buf());
                }

                @Override
                public void onError(Throwable t) {
                    if (!deframed.isDone()) {
                        deframed.completeExceptionally(t);
                    }
                }

                @Override
                public void onComplete() {
                    if (!deframed.isDone()) {
                        deframed.complete(Unpooled.EMPTY_BUFFER);
                    }
                }
            };
        }

        private static CompletableFuture<ByteBuf> handleMessage(ByteBuf message) {
            final byte[] bytes;
            try {
                bytes = ByteBufUtil.getBytes(message);
            } finally {
                message.release();
            }
            final SimpleRequest request;
            try {
                request = SimpleRequest.parseFrom(bytes);
            } catch (InvalidProtocolBufferException e) {
                throw new UncheckedIOException(e);
            }
            final SimpleResponse response = SimpleResponse.newBuilder()
                                                          .setPayload(request.getPayload())
                                                          .build();
            return CompletableFuture.completedFuture(
                    Unpooled.wrappedBuffer(response.toByteArray()));
        }
    }
}
