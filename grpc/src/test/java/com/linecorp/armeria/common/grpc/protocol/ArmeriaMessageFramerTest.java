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

import static com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer.COMPRESSED_TRAILERS;
import static com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer.UNCOMPRESSED_TRAILERS;
import static com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil.serializeTrailersAsMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;

import io.grpc.Codec.Gzip;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

class ArmeriaMessageFramerTest {

    private ArmeriaMessageFramer framer;

    @BeforeEach
    void setUp(TestInfo info) {
        final Method method = info.getTestMethod().get();
        final boolean decodeBase64 = method.getName().contains("Base64");
        framer = new ArmeriaMessageFramer(UnpooledByteBufAllocator.DEFAULT, 1024, decodeBase64);
    }

    @AfterEach
    void close() {
        framer.close();
    }

    @Test
    void writeUncompressed() {
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        try (HttpData framed = framer.writePayload(buf)) {
            assertThat(framed.array()).isEqualTo(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf()));
            assertThat(buf.refCnt()).isEqualTo(0);
        }
    }

    @Test
    void encodeBase64_writeUncompressedAnd() {
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                UnpooledByteBufAllocator.DEFAULT, 1024, true);
             HttpData framed = framer.writePayload(buf)) {
            assertThat(Base64.getDecoder().decode(framed.array()))
                    .isEqualTo(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf()));
            assertThat(buf.refCnt()).isEqualTo(0);
        }
    }

    @Test
    void writeCompressed() {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        try (HttpData framed = framer.writePayload(buf)) {
            assertThat(framed.array()).isEqualTo(GrpcTestUtil.compressedFrame(GrpcTestUtil.requestByteBuf()));
            assertThat(buf.refCnt()).isEqualTo(0);
        }
    }

    @Test
    void encodeBase64_writeCompressed() {
        try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                UnpooledByteBufAllocator.DEFAULT, 1024, true)) {
            framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
            framer.setMessageCompression(true);
            final ByteBuf buf = GrpcTestUtil.requestByteBuf();
            try (HttpData framed = framer.writePayload(buf)) {
                assertThat(Base64.getDecoder().decode(framed.array()))
                        .isEqualTo(GrpcTestUtil.compressedFrame(GrpcTestUtil.requestByteBuf()));
                assertThat(buf.refCnt()).isEqualTo(0);
            }
        }
    }

    @Test
    void writeUncompressedTrailers() {
        final ByteBuf serialized = serializedTrailers();
        try (HttpData framed = framer.writePayload(serialized, true)) {
            assertThat(framed.isEndOfStream()).isTrue();
            assertThat(framed.array()).isEqualTo(
                    GrpcTestUtil.uncompressedFrame(serializedTrailers(), UNCOMPRESSED_TRAILERS));
            assertThat(serialized.refCnt()).isEqualTo(0);
        }
    }

    @Test
    void writeCompressedTrailers() {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final ByteBuf serialized = serializedTrailers();
        try (HttpData framed = framer.writePayload(serialized, true)) {
            assertThat(framed.isEndOfStream()).isTrue();
            assertThat(framed.array()).isEqualTo(
                    GrpcTestUtil.compressedFrame(serializedTrailers(), COMPRESSED_TRAILERS));
            assertThat(serialized.refCnt()).isEqualTo(0);
        }
    }

    @Test
    void emptyNotCompressed() {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final ByteBuf buf = GrpcTestUtil.protoByteBuf(SimpleRequest.getDefaultInstance());
        assertThat(buf.readableBytes()).isEqualTo(0);
        try (HttpData framed = framer.writePayload(buf)) {
            assertThat(framed.array()).isEqualTo(GrpcTestUtil.uncompressedFrame(
                    GrpcTestUtil.protoByteBuf(SimpleRequest.getDefaultInstance())));
            assertThat(buf.refCnt()).isEqualTo(0);
        }
    }

    @Test
    void tooLargeUncompressed() {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        assertThatThrownBy(() -> framer.writePayload(GrpcTestUtil.protoByteBuf(request)))
                .isInstanceOf(ArmeriaStatusException.class);
    }

    @Test
    void encodeBase64_tooLargeUncompressed() {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 758)))) // Encoded size is 1028.
                             .build();
        assertThatThrownBy(() -> framer.writePayload(GrpcTestUtil.protoByteBuf(request)))
                .isInstanceOf(ArmeriaStatusException.class);
    }

    @Test
    void notTooLargeCompressed() {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        try (HttpData framed = framer.writePayload(GrpcTestUtil.protoByteBuf(request))) {
            assertThat(framed.array()).isEqualTo(
                    GrpcTestUtil.compressedFrame(GrpcTestUtil.protoByteBuf(request)));
        }
    }

    @Test
    void tooLargeCompressed() {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final Random random = new Random(1);
        final byte[] payload = new byte[1024];
        random.nextBytes(payload);
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(payload)))
                             .build();
        assertThatThrownBy(() -> framer.writePayload(GrpcTestUtil.protoByteBuf(request)))
                .isInstanceOf(ArmeriaStatusException.class);
    }

    private static ByteBuf serializedTrailers() {
        final ResponseHeadersBuilder trailersBuilder = ResponseHeaders.builder(200).contentType(
                GrpcSerializationFormats.PROTO.mediaType());
        GrpcTrailersUtil.addStatusMessageToTrailers(trailersBuilder, StatusCodes.OK, null);
        return serializeTrailersAsMessage(ByteBufAllocator.DEFAULT, trailersBuilder.build());
    }
}
