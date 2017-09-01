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

import java.util.zip.GZIPOutputStream;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public final class GrpcTestUtil {
    public static final SimpleRequest REQUEST_MESSAGE =
            SimpleRequest.newBuilder()
                         .setPayload(Payload.newBuilder()
                                            .setBody(ByteString.copyFromUtf8("armeria and grpc")))
                         .setResponseSize(200)
                         .setFillUsername(true)
                         .build();
    public static final SimpleResponse RESPONSE_MESSAGE =
            SimpleResponse.newBuilder()
                          .setPayload(Payload.newBuilder()
                                             .setBody(ByteString.copyFromUtf8("grpc and armeria")))
                          .build();

    public static byte[] uncompressedResponseBytes() throws Exception {
        return uncompressedFrame(protoByteBuf(RESPONSE_MESSAGE));
    }

    public static byte[] compressedResponseBytes() throws Exception {
        return compressedFrame(protoByteBuf(RESPONSE_MESSAGE));
    }

    public static ByteBuf requestByteBuf() throws Exception {
        return protoByteBuf(REQUEST_MESSAGE);
    }

    public static ByteBuf protoByteBuf(MessageLite msg) throws Exception {
        ByteBuf buf = Unpooled.buffer();
        try (ByteBufOutputStream os = new ByteBufOutputStream(buf)) {
            msg.writeTo(os);
        }
        return buf;
    }

    public static byte[] uncompressedFrame(ByteBuf proto) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0);
        buf.writeInt(proto.readableBytes());
        buf.writeBytes(proto);
        proto.release();
        byte[] result = ByteBufUtil.getBytes(buf);
        buf.release();
        return result;
    }

    public static byte[] compressedFrame(ByteBuf uncompressed) throws Exception {
        ByteBuf compressed = Unpooled.buffer();
        try (ByteBufInputStream is = new ByteBufInputStream(uncompressed, true);
             GZIPOutputStream os = new GZIPOutputStream(new ByteBufOutputStream(compressed))) {
            ByteStreams.copy(is, os);
        }
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(1);
        buf.writeInt(compressed.readableBytes());
        buf.writeBytes(compressed);
        compressed.release();
        byte[] result = ByteBufUtil.getBytes(buf);
        buf.release();
        return result;
    }

    private GrpcTestUtil() {}
}
