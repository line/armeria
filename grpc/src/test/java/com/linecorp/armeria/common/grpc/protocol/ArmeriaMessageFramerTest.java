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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.grpc.Codec.Gzip;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

public class ArmeriaMessageFramerTest {

    private ArmeriaMessageFramer framer;

    @Before
    public void setUp() {
        framer = new ArmeriaMessageFramer(UnpooledByteBufAllocator.DEFAULT, 1024);
    }

    @After
    public void close() {
        framer.close();
    }

    @Test
    public void writeUncompressed() throws Exception {
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        final ByteBufHttpData framed = framer.writePayload(buf);
        assertThat(framed.array()).isEqualTo(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf()));
        assertThat(buf.refCnt()).isEqualTo(0);
        framed.release();
    }

    @Test
    public void compressed() throws Exception {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        final ByteBufHttpData framed = framer.writePayload(buf);
        assertThat(framed.array()).isEqualTo(GrpcTestUtil.compressedFrame(GrpcTestUtil.requestByteBuf()));
        assertThat(buf.refCnt()).isEqualTo(0);
        framed.release();
    }

    @Test
    public void emptyNotCompressed() throws Exception {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final ByteBuf buf = GrpcTestUtil.protoByteBuf(SimpleRequest.getDefaultInstance());
        assertThat(buf.readableBytes()).isEqualTo(0);
        final ByteBufHttpData framed = framer.writePayload(buf);
        assertThat(framed.array()).isEqualTo(GrpcTestUtil.uncompressedFrame(
                GrpcTestUtil.protoByteBuf(SimpleRequest.getDefaultInstance())));
        assertThat(buf.refCnt()).isEqualTo(0);
        framed.release();
    }

    @Test
    public void tooLargeUncompressed() throws Exception {
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
    public void notTooLargeCompressed() throws Exception {
        framer.setCompressor(ForwardingCompressor.forGrpc(new Gzip()));
        framer.setMessageCompression(true);
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        final ByteBufHttpData framed = framer.writePayload(GrpcTestUtil.protoByteBuf(request));
        assertThat(framed.array()).isEqualTo(GrpcTestUtil.compressedFrame(GrpcTestUtil.protoByteBuf(request)));
        framed.release();
    }

    @Test
    public void tooLargeCompressed() throws Exception {
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
}
