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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.common.thrift.AbstractTByteBufTransport.ReadBytesNotAvailableException;
import com.linecorp.armeria.internal.server.thrift.ThriftVersionDetector;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class TByteBufTransportTest {

    private static final int DECLARED_LENGTH = 4 * 1024 * 1024;
    private static final String VALUE = "payload";
    private static final String DERIVED_VALUE = Strings.repeat("é", 64);

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void fieldLengthLimit_binary(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, BINARY, DERIVED_VALUE.getBytes(UTF_8).length - 1);
            protocol.writeString(DERIVED_VALUE);

            final Throwable cause = catchThrowable(() -> readField(protocol, binary));
            assertThat(cause).isInstanceOf(TProtocolException.class);
            assertThat(((TProtocolException) cause).getType()).isEqualTo(TProtocolException.SIZE_LIMIT);
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void fieldLengthLimit_compact(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, COMPACT, DERIVED_VALUE.getBytes(UTF_8).length - 1);
            protocol.writeString(DERIVED_VALUE);

            final Throwable cause = catchThrowable(() -> readField(protocol, binary));
            assertThat(cause).isInstanceOf(TProtocolException.class);
            assertThat(((TProtocolException) cause).getType()).isEqualTo(TProtocolException.SIZE_LIMIT);
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndBuffers")
    void stringAtLimit(SerializationFormat format, boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, format, VALUE.length());
            protocol.writeString(VALUE);

            assertThat(protocol.readString()).isEqualTo(VALUE);
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndBuffers")
    void binaryAtLimit(SerializationFormat format, boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, format, VALUE.length());
            protocol.writeString(VALUE);

            assertThat(protocol.readBinary()).isEqualTo(ByteBuffer.wrap(VALUE.getBytes(UTF_8)));
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("formats")
    void slicedBuffer(SerializationFormat format) throws Exception {
        final ByteBuf encoded = Unpooled.buffer();

        try {
            writeDerivedField(encoded, format);
            final ByteBuf buf = encoded.slice();
            final TProtocol protocol = newProtocol(buf, format, 0);

            assertThat(protocol.readString()).isEqualTo(DERIVED_VALUE);
            assertThat(buf.isReadable()).isFalse();
        } finally {
            encoded.release();
        }
    }

    @ParameterizedTest
    @MethodSource("formats")
    void compositeBuffer(SerializationFormat format) throws Exception {
        final ByteBuf encoded = Unpooled.buffer();

        try {
            writeDerivedField(encoded, format);
            final int start = encoded.readerIndex();

            // Split the length prefix across components to use reads across buffer boundaries.
            final ByteBuf buf = Unpooled.wrappedBuffer(
                    encoded.retainedSlice(start, 1),
                    encoded.retainedSlice(start + 1, encoded.readableBytes() - 1));

            try {
                final TProtocol protocol = newProtocol(buf, format, 0);
                assertThat(protocol.readBinary()).isEqualTo(ByteBuffer.wrap(DERIVED_VALUE.getBytes(UTF_8)));
                assertThat(buf.isReadable()).isFalse();
            } finally {
                buf.release();
            }
        } finally {
            encoded.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void truncatedField_binary(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            buf.writeInt(DECLARED_LENGTH);
            final TProtocol protocol = newProtocol(buf, BINARY, DECLARED_LENGTH * 2);
            assertThatThrownBy(() -> readField(protocol, binary))
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void truncatedField_compact(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            // The varint encoding of DECLARED_LENGTH (2^22).
            buf.writeBytes(new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x02 });
            final TProtocol protocol = newProtocol(buf, COMPACT, DECLARED_LENGTH * 2);
            assertThatThrownBy(() -> readField(protocol, binary))
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void truncatedPayload_binary(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, BINARY, VALUE.length());
            protocol.writeString(VALUE);
            buf.writerIndex(buf.writerIndex() - 1);

            // Counting the length prefix as payload would miss this truncation and allocate first.
            assertThatThrownBy(() -> readField(protocol, binary))
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void truncatedPayload_compact(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, COMPACT, VALUE.length());
            protocol.writeString(VALUE);
            buf.writerIndex(buf.writerIndex() - 1);

            // Counting the length prefix as payload would miss this truncation and allocate first.
            assertThatThrownBy(() -> readField(protocol, binary))
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void negativeLength_binary(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);
        final ByteBuf stockBuf = newBuffer(direct);

        try {
            buf.writeInt(-1);
            stockBuf.writeInt(-1);

            // Use the same buffer layout: upstream fast paths can throw different errors from slow paths.
            final TByteBufTransport stockTransport = new TByteBufTransport(stockBuf);
            final TProtocol stock = new TBinaryProtocol(stockTransport);
            final TProtocol protocol = newProtocol(buf, BINARY, 0);

            final Throwable expected = catchThrowable(() -> readField(stock, binary));
            assertThat(expected).isNotNull();
            final Throwable actual = catchThrowable(() -> readField(protocol, binary));
            assertThat(actual).hasSameClassAs(expected);

            if (expected instanceof TProtocolException) {
                assertThat(((TProtocolException) actual).getType())
                        .isEqualTo(((TProtocolException) expected).getType());
            }
        } finally {
            buf.release();
            stockBuf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void negativeLength_compact(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);
        final ByteBuf stockBuf = newBuffer(direct);

        try {
            // The raw encoding of -1 as an unsigned compact varint.
            final byte[] length = { -1, -1, -1, -1, 0x0f };
            buf.writeBytes(length);
            stockBuf.writeBytes(length);

            // Use the same buffer layout: upstream fast paths can throw different errors from slow paths.
            final TByteBufTransport stockTransport = new TByteBufTransport(stockBuf);
            final TProtocol stock = new TCompactProtocol(stockTransport);
            final TProtocol protocol = newProtocol(buf, COMPACT, 0);

            final Throwable expected = catchThrowable(() -> readField(stock, binary));
            assertThat(expected).isNotNull();
            final Throwable actual = catchThrowable(() -> readField(protocol, binary));
            assertThat(actual).hasSameClassAs(expected);

            if (expected instanceof TProtocolException) {
                assertThat(((TProtocolException) actual).getType())
                        .isEqualTo(((TProtocolException) expected).getType());
            }
        } finally {
            buf.release();
            stockBuf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndBuffers")
    void messageNameLengthLimit(SerializationFormat format, boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, format, DERIVED_VALUE.getBytes(UTF_8).length - 1);
            protocol.writeMessageBegin(new TMessage(DERIVED_VALUE, TMessageType.CALL, 0));

            assertThatThrownBy(protocol::readMessageBegin)
                    .isInstanceOf(TProtocolException.class)
                    .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                            .isEqualTo(TProtocolException.SIZE_LIMIT));
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void messageNameLengthLimit_unversionedBinary(boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, BINARY, VALUE.length() - 1);
            protocol.writeString(VALUE);
            protocol.writeByte(TMessageType.CALL);
            protocol.writeI32(0);

            final Throwable cause = catchThrowable(protocol::readMessageBegin);
            assertThat(cause).isInstanceOf(TProtocolException.class)
                             .hasMessage("Length exceeded max allowed: " + VALUE.length());
            assertThat(((TProtocolException) cause).getType()).isEqualTo(TProtocolException.SIZE_LIMIT);
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void truncatedMessageName_binary(boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            buf.writeInt(0x80010001); // Binary V1, CALL.
            buf.writeInt(DECLARED_LENGTH);
            final TProtocol protocol = newProtocol(buf, BINARY, DECLARED_LENGTH * 2);
            assertThatThrownBy(protocol::readMessageBegin)
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void truncatedMessageName_unversionedBinary(boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            buf.writeInt(DECLARED_LENGTH);
            final TProtocol protocol = newProtocol(buf, BINARY, DECLARED_LENGTH * 2);
            assertThatThrownBy(protocol::readMessageBegin)
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void truncatedMessageName_compact(boolean direct) throws Exception {
        final ByteBuf buf = newBuffer(direct);

        try {
            // CALL, sequence ID 0.
            buf.writeBytes(new byte[] { (byte) 0x82, 0x21, 0x00 });
            // The varint encoding of DECLARED_LENGTH (2^22).
            buf.writeBytes(new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x02 });

            final TProtocol protocol = newProtocol(buf, COMPACT, DECLARED_LENGTH * 2);
            assertThatThrownBy(protocol::readMessageBegin)
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void compactLengthBoundaries(boolean direct, boolean binary) throws Exception {
        final ByteBuf buf = newBuffer(direct);
        try {
            final String[] values = {
                    "", "\0", "é", Strings.repeat("a", 127), Strings.repeat("é", 64),
                    Strings.repeat("a", 16383), Strings.repeat("é", 8192)
            };

            final TProtocol protocol = newProtocol(buf, COMPACT, 0);
            for (String value : values) {
                protocol.writeString(value);
                protocol.writeI32(Integer.MIN_VALUE);
            }

            for (String value : values) {
                final Object expected = binary ? ByteBuffer.wrap(value.getBytes(UTF_8)) : value;
                assertThat(readField(protocol, binary)).isEqualTo(expected);
                assertThat(protocol.readI32()).isEqualTo(Integer.MIN_VALUE);
            }
            assertThat(buf.isReadable()).isFalse();
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("formatsAndFieldTypes")
    void truncatedSlicedBuffer(SerializationFormat format, boolean binary) throws Exception {
        final ByteBuf encoded = Unpooled.buffer();

        try {
            writeDerivedField(encoded, format);
            encoded.writerIndex(encoded.writerIndex() - 1);
            final ByteBuf buf = encoded.slice();
            final TProtocol protocol = newProtocol(buf, format, 0);

            assertThatThrownBy(() -> readField(protocol, binary))
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            encoded.release();
        }
    }

    @ParameterizedTest
    @MethodSource("formatsAndFieldTypes")
    void truncatedCompositeBuffer(SerializationFormat format, boolean binary) throws Exception {
        final ByteBuf encoded = Unpooled.buffer();

        try {
            writeDerivedField(encoded, format);
            encoded.writerIndex(encoded.writerIndex() - 1);
            final int start = encoded.readerIndex();

            // Split the length prefix across components to check the non-array read path.
            final ByteBuf buf = Unpooled.wrappedBuffer(
                    encoded.retainedSlice(start, 1),
                    encoded.retainedSlice(start + 1, encoded.readableBytes() - 1));

            try {
                final TProtocol protocol = newProtocol(buf, format, 0);
                assertThatThrownBy(() -> readField(protocol, binary))
                        .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                        .hasMessage("unexpected end of frame");
            } finally {
                buf.release();
            }
        } finally {
            encoded.release();
        }
    }

    @ParameterizedTest
    @MethodSource("bufferParameters")
    void tooLongCompactLength(boolean direct, boolean binary) throws Exception {
        assumeTrue(ThriftVersionDetector.minorVersion() < 14);
        final ByteBuf buf = newBuffer(direct);

        try {
            // Older decoders accept more than five varint bytes. This still declares 2^22 bytes.
            buf.writeBytes(new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80,
                                       (byte) 0x82, (byte) 0x80, 0x00 });
            final TProtocol protocol = newProtocol(buf, COMPACT, 0);

            assertThatThrownBy(() -> readField(protocol, binary))
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void incompleteCompactLength(boolean direct) {
        assumeTrue(ThriftVersionDetector.minorVersion() < 14);
        final ByteBuf buf = newBuffer(direct);

        try {
            buf.writeBytes(new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80,
                                       (byte) 0x80, (byte) 0x80 });
            final TProtocol protocol = newProtocol(buf, COMPACT, 0);

            assertThatThrownBy(protocol::readString)
                    .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                    .hasMessage("unexpected end of frame");
        } finally {
            buf.release();
        }
    }

    private static Stream<SerializationFormat> formats() {
        return Stream.of(BINARY, COMPACT);
    }

    private static Stream<Arguments> protocolsAndBuffers() {
        return Stream.of(
                Arguments.of(BINARY, false),
                Arguments.of(BINARY, true),
                Arguments.of(COMPACT, false),
                Arguments.of(COMPACT, true));
    }

    private static Stream<Arguments> bufferParameters() {
        return Stream.of(
                Arguments.of(false, false),
                Arguments.of(false, true),
                Arguments.of(true, false),
                Arguments.of(true, true));
    }

    private static Stream<Arguments> formatsAndFieldTypes() {
        return Stream.of(
                Arguments.of(BINARY, false),
                Arguments.of(BINARY, true),
                Arguments.of(COMPACT, false),
                Arguments.of(COMPACT, true));
    }

    private static ByteBuf newBuffer(boolean direct) {
        return direct ? Unpooled.directBuffer() : Unpooled.buffer();
    }

    private static TProtocol newProtocol(ByteBuf buf, SerializationFormat format, int maxStringLength) {
        return ThriftSerializationFormats.protocolFactory(format, maxStringLength, 0)
                                         .getProtocol(new TByteBufTransport(buf));
    }

    private static void writeDerivedField(ByteBuf buf, SerializationFormat format) throws TException {
        // Leave a non-zero array offset in the slice. TCompact encodes the byte length as a two-byte varint.
        buf.writeInt(Integer.MAX_VALUE);
        buf.skipBytes(Integer.BYTES);
        newProtocol(buf, format, 0).writeString(DERIVED_VALUE);
    }

    private static Object readField(TProtocol protocol, boolean binary) throws TException {
        return binary ? protocol.readBinary() : protocol.readString();
    }
}
