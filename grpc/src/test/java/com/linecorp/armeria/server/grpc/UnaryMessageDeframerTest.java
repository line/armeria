/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testng.util.Strings;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;

class UnaryMessageDeframerTest {

    @CsvSource({"80", "101"})
    @ParameterizedTest
    void deframe(int size) {
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final UnaryMessageDeframer deframer = new UnaryMessageDeframer(alloc, 100, false);

        final Payload payload = Payload.newBuilder()
                                       .setBody(ByteString.copyFromUtf8(Strings.repeat("0", size)))
                                       .build();
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(payload)
                                                   .build();
        final byte[] body = request.toByteArray();
        final ByteBuf byteBuf = alloc.buffer();
        // https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests
        byteBuf.writeByte(0); // uncompressed
        byteBuf.writeInt(body.length); // length
        byteBuf.writeBytes(body);

        if (size > 100) {
            assertThatThrownBy(() -> {
                deframer.deframe(HttpData.wrap(byteBuf));
            })
                    .isInstanceOf(ArmeriaStatusException.class)
                    .hasMessageContaining("exceeds maximum: 100.");
            assertThat(byteBuf.refCnt()).isZero();
        } else {
            final DeframedMessage deframed = deframer.deframe(HttpData.wrap(byteBuf));
            final ByteBuf deframedBuf = deframed.buf();
            assertThat(ByteBufUtil.getBytes(deframedBuf)).isEqualTo(body);
            deframedBuf.release();
            assertThat(deframedBuf.refCnt()).isZero();
            assertThat(byteBuf.refCnt()).isZero();
        }
    }
}
