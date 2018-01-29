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

package com.linecorp.armeria.internal.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;

public class GrpcMessageMarshallerTest {

    private GrpcMessageMarshaller<SimpleRequest, SimpleResponse> marshaller;

    @Before
    public void setUp() {
        marshaller = new GrpcMessageMarshaller<>(
                ByteBufAllocator.DEFAULT,
                GrpcSerializationFormats.PROTO,
                TestServiceGrpc.getUnaryCallMethod(),
                MessageMarshaller.builder()
                                 .register(SimpleRequest.getDefaultInstance())
                                 .register(SimpleResponse.getDefaultInstance())
                                 .build(),
                false);
    }

    @Test
    public void serializeRequest() throws Exception {
        ByteBuf serialized = marshaller.serializeRequest(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(ByteBufUtil.getBytes(serialized))
                .containsExactly(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
        serialized.release();
    }

    @Test
    public void deserializeRequest_byteBuf() throws Exception {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.REQUEST_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
        SimpleRequest request = marshaller.deserializeRequest(new ByteBufOrStream(buf));
        assertThat(request).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(0);
    }

    @Test
    public void deserializeRequest_wrappedByteBuf() throws Exception {
        marshaller = new GrpcMessageMarshaller<>(
                ByteBufAllocator.DEFAULT,
                GrpcSerializationFormats.PROTO,
                TestServiceGrpc.getUnaryCallMethod(),
                MessageMarshaller.builder()
                                 .register(SimpleRequest.getDefaultInstance())
                                 .register(SimpleResponse.getDefaultInstance())
                                 .build(),
                true);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.REQUEST_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
        SimpleRequest request = marshaller.deserializeRequest(new ByteBufOrStream(buf));
        assertThat(request).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(1);
    }

    @Test
    public void deserializeRequest_stream() throws Exception {
        SimpleRequest request = marshaller.deserializeRequest(
                new ByteBufOrStream(new ByteArrayInputStream(GrpcTestUtil.REQUEST_MESSAGE.toByteArray())));
        assertThat(request).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE);
    }

    @Test
    public void serializeResponse() throws Exception {
        ByteBuf serialized = marshaller.serializeResponse(GrpcTestUtil.RESPONSE_MESSAGE);
        assertThat(ByteBufUtil.getBytes(serialized))
                .containsExactly(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray());
        serialized.release();
    }

    @Test
    public void deserializeResponse_bytebuf() throws Exception {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.RESPONSE_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray());
        SimpleResponse response = marshaller.deserializeResponse(new ByteBufOrStream(buf));
        assertThat(response).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(0);
    }

    @Test
    public void deserializeResponse_wrappedByteBuf() throws Exception {
        marshaller = new GrpcMessageMarshaller<>(
                ByteBufAllocator.DEFAULT,
                GrpcSerializationFormats.PROTO,
                TestServiceGrpc.getUnaryCallMethod(),
                MessageMarshaller.builder()
                                 .register(SimpleRequest.getDefaultInstance())
                                 .register(SimpleResponse.getDefaultInstance())
                                 .build(),
                true);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(GrpcTestUtil.RESPONSE_MESSAGE.getSerializedSize());
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.writeBytes(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray());
        SimpleResponse response = marshaller.deserializeResponse(new ByteBufOrStream(buf));
        assertThat(response).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
        assertThat(buf.refCnt()).isEqualTo(1);
    }

    @Test
    public void deserializeResponse_stream() throws Exception {
        SimpleResponse response = marshaller.deserializeResponse(
                new ByteBufOrStream(new ByteArrayInputStream(GrpcTestUtil.RESPONSE_MESSAGE.toByteArray())));
        assertThat(response).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
    }
}
