/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ThriftProtocolUtilTest {

    private static final String NAME = "message";

    @ParameterizedTest
    @MethodSource("formats")
    void messageHeader_atLimit(SerializationFormat format) throws Exception {
        final int maxStringLength = NAME.length();
        final ByteBuf buf = Unpooled.buffer();

        try {
            final TProtocol writer = newProtocol(buf, format, maxStringLength);
            writer.writeMessageBegin(new TMessage(NAME, TMessageType.CALL, Integer.MIN_VALUE));
            writer.writeMessageEnd();

            final TProtocol protocol = newProtocol(buf, format, maxStringLength);
            assertMessage(readMessageBegin(protocol, format, buf, maxStringLength));
            protocol.readMessageEnd();
            assertThat(buf.isReadable()).isFalse();
        } finally {
            buf.release();
        }
    }

    @Test
    void unversionedMessageHeader_withoutLimit() throws Exception {
        final ByteBuf buf = Unpooled.buffer();

        try {
            final TProtocol protocol = newProtocol(buf, BINARY, 0);
            writeUnversionedBinaryHeader(protocol);

            assertMessage(readMessageBegin(protocol, BINARY, buf, 0));
            assertThat(buf.isReadable()).isFalse();
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7 })
    void truncatedHeader(int availableBytes) {
        final ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeInt(0x80010001); // Binary V1, CALL.
            buf.writeInt(NAME.length());
            buf.writerIndex(availableBytes);
            final TProtocol protocol = newProtocol(buf, ThriftSerializationFormats.BINARY, 1);

            // A truncated primitive is not an oversized name, even if its size exceeds the limit.
            assertThatThrownBy(() -> readMessageBegin(
                    protocol, ThriftSerializationFormats.BINARY, buf, 1))
                    .isInstanceOf(TTransportException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @Test
    void invalidVersion() {
        final ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeInt(0x80020001);
            final TProtocol protocol = newProtocol(buf, ThriftSerializationFormats.BINARY, 1);

            assertThatThrownBy(() -> readMessageBegin(
                    protocol, ThriftSerializationFormats.BINARY, buf, 1))
                    .isInstanceOf(TProtocolException.class)
                    .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                            .isEqualTo(TProtocolException.BAD_VERSION));
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, Integer.BYTES })
    void unversionedName_exceedsLimit(int readerIndex) throws Exception {
        final ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeZero(readerIndex);
            buf.skipBytes(readerIndex);
            final TProtocol protocol = newProtocol(buf, ThriftSerializationFormats.BINARY, NAME.length() - 1);
            writeUnversionedBinaryHeader(protocol);

            assertThatThrownBy(() -> readMessageBegin(
                    protocol, ThriftSerializationFormats.BINARY, buf, NAME.length() - 1))
                    .isInstanceOf(TProtocolException.class)
                    .hasMessage("Length exceeded max allowed: " + NAME.length())
                    .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                            .isEqualTo(TProtocolException.SIZE_LIMIT));
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, Integer.BYTES })
    void unversionedName_atLimit(int readerIndex) throws Exception {
        final ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeZero(readerIndex);
            buf.skipBytes(readerIndex);
            final TProtocol protocol = newProtocol(buf, BINARY, NAME.length());
            writeUnversionedBinaryHeader(protocol);

            assertMessage(readMessageBegin(protocol, BINARY, buf, NAME.length()));
            assertThat(buf.isReadable()).isFalse();
        } finally {
            buf.release();
        }
    }

    private static Stream<SerializationFormat> formats() {
        return Stream.of(BINARY, COMPACT, JSON);
    }

    private static TMessage readMessageBegin(TProtocol protocol, SerializationFormat format,
                                             ByteBuf buf, int maxStringLength) throws TException {
        ThriftProtocolUtil.maybeCheckMessageLength(format, buf, maxStringLength);
        return protocol.readMessageBegin();
    }

    private static TProtocol newProtocol(ByteBuf buf, SerializationFormat format, int maxStringLength) {
        return ThriftSerializationFormats.protocolFactory(format, maxStringLength, 0)
                                         .getProtocol(new TByteBufTransport(buf));
    }

    private static void writeUnversionedBinaryHeader(TProtocol protocol) throws Exception {
        protocol.writeString(NAME);
        protocol.writeByte(TMessageType.CALL);
        protocol.writeI32(Integer.MIN_VALUE);
    }

    private static void assertMessage(TMessage message) {
        assertThat(message.name).isEqualTo(NAME);
        assertThat(message.type).isEqualTo(TMessageType.CALL);
        assertThat(message.seqid).isEqualTo(Integer.MIN_VALUE);
    }
}
