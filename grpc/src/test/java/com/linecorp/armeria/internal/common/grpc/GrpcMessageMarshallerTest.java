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

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.stream.Stream;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;

class GrpcMessageMarshallerTest {

    private static Stream<GrpcJsonMarshaller> grpcJsonMarshallerStream() {
        final DefaultJsonMarshaller protobufJacksonJsonMarshaller =
                new DefaultJsonMarshaller(MessageMarshaller.builder()
                                                           .register(SimpleRequest.getDefaultInstance())
                                                           .register(SimpleResponse.getDefaultInstance())
                                                           .build());
        return Stream.of(protobufJacksonJsonMarshaller, GrpcJsonMarshaller.ofGson());
    }

    private static Stream<Arguments> jsonMarshallerArgs() {
        return grpcJsonMarshallerStream().map(Arguments::of);
    }

    static GrpcMessageMarshaller<SimpleRequest, SimpleResponse> messageMarshaller(
            GrpcJsonMarshaller grpcJsonMarshaller) {
        return new GrpcMessageMarshaller<>(ByteBufAllocator.DEFAULT,
                                           GrpcSerializationFormats.PROTO,
                                           TestServiceGrpc.getUnaryCallMethod(),
                                           grpcJsonMarshaller,
                                           false,
                                           false);
    }

    private static Stream<Arguments> messageMarshallerArgs() {
        return grpcJsonMarshallerStream().map(GrpcMessageMarshallerTest::messageMarshaller)
                                         .map(Arguments::of);
    }

    static GrpcMessageMarshaller<SimpleRequest, SimpleResponse> messageMarshallerWithMethodMarshaller(
            GrpcJsonMarshaller grpcJsonMarshaller) {
        return new GrpcMessageMarshaller<>(ByteBufAllocator.DEFAULT,
                                           GrpcSerializationFormats.PROTO,
                                           TestServiceGrpc.getUnaryCallMethod(),
                                           grpcJsonMarshaller,
                                           false,
                                           true);
    }

    private static Stream<Arguments> messageMarshallerArgsWithMethodMarshaller() {
        return grpcJsonMarshallerStream().map(GrpcMessageMarshallerTest::messageMarshallerWithMethodMarshaller)
                                         .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource({"messageMarshallerArgs", "messageMarshallerArgsWithMethodMarshaller"})
    void serializeRequest(GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller) throws Exception {
        final ByteBuf serialized = marshaller.serializeRequest(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(ByteBufUtil.getBytes(serialized))
                .containsExactly(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
        serialized.release();
    }

    @ParameterizedTest
    @MethodSource({"messageMarshallerArgs", "messageMarshallerArgsWithMethodMarshaller"})
    void deserializeRequest_byteBuf(GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller)
            throws Exception {
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.REQUEST_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
        final SimpleRequest request = marshaller.deserializeRequest(new DeframedMessage(buf, 0), false);
        assertThat(request).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("jsonMarshallerArgs")
    void deserializeRequest_wrappedByteBuf(GrpcJsonMarshaller grpcJsonMarshaller) throws Exception {
        final GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller =
                new GrpcMessageMarshaller<>(ByteBufAllocator.DEFAULT,
                                            GrpcSerializationFormats.PROTO,
                                            TestServiceGrpc.getUnaryCallMethod(),
                                            grpcJsonMarshaller,
                                            true,
                                            false);
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.REQUEST_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
        final SimpleRequest request = marshaller.deserializeRequest(new DeframedMessage(buf, 0), false);
        assertThat(request).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.release();
    }

    @ParameterizedTest
    @MethodSource({"messageMarshallerArgs", "messageMarshallerArgsWithMethodMarshaller"})
    void deserializeRequest_stream(GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller)
            throws Exception {
        final SimpleRequest request = marshaller.deserializeRequest(
                new DeframedMessage(new ByteArrayInputStream(GrpcTestUtil.REQUEST_MESSAGE.toByteArray()), 0),
                false);
        assertThat(request).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE);
    }

    @ParameterizedTest
    @MethodSource({"messageMarshallerArgs", "messageMarshallerArgsWithMethodMarshaller"})
    void serializeResponse(GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller) throws Exception {
        final ByteBuf serialized = marshaller.serializeResponse(GrpcTestUtil.RESPONSE_MESSAGE);
        assertThat(ByteBufUtil.getBytes(serialized))
                .containsExactly(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray());
        serialized.release();
    }

    @ParameterizedTest
    @MethodSource({"messageMarshallerArgs", "messageMarshallerArgsWithMethodMarshaller"})
    void deserializeResponse_bytebuf(GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller)
            throws Exception {
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.RESPONSE_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray());
        final SimpleResponse response = marshaller.deserializeResponse(new DeframedMessage(buf, 0), false);
        assertThat(response).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("jsonMarshallerArgs")
    void deserializeResponse_wrappedByteBuf(GrpcJsonMarshaller grpcJsonMarshaller) throws Exception {
        final GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller =
                new GrpcMessageMarshaller<>(ByteBufAllocator.DEFAULT,
                                            GrpcSerializationFormats.PROTO,
                                            TestServiceGrpc.getUnaryCallMethod(),
                                            grpcJsonMarshaller,
                                            true,
                                            true);
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.RESPONSE_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray());
        final SimpleResponse response = marshaller.deserializeResponse(new DeframedMessage(buf, 0), false);
        assertThat(response).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.release();
    }

    @ParameterizedTest
    @MethodSource({"messageMarshallerArgs", "messageMarshallerArgsWithMethodMarshaller"})
    void deserializeResponse_stream(GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller)
            throws Exception {
        final SimpleResponse response = marshaller.deserializeResponse(
                new DeframedMessage(new ByteArrayInputStream(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray()), 0),
                false);
        assertThat(response).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
    }
}
