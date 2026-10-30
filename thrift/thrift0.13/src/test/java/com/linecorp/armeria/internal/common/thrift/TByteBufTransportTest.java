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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class TByteBufTransportTest {

    private static final String VALUE = "payload";
    private static final String DERIVED_VALUE = Strings.repeat("é", 64);

    @ParameterizedTest
    @MethodSource("formats")
    void stringLengthLimit(SerializationFormat format) throws Exception {
        assumeTrue(supportsLengthLimits(), "Thrift 0.9.1 does not support field-length limits");
        // Direct buffers user binary reader's length check on every Thrift version.
        final ByteBuf buf = Unpooled.directBuffer();

        try {
            final TProtocol protocol = newProtocol(buf, format, VALUE.length() - 1);
            protocol.writeString(VALUE);

            assertThatThrownBy(protocol::readString)
                    .isInstanceOf(TProtocolException.class)
                    .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                            .isEqualTo(TProtocolException.SIZE_LIMIT));
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void binaryLengthLimit(boolean direct) throws Exception {
        assumeTrue(supportsLengthLimits(), "Thrift 0.9.1 does not support field-length limits");
        final ByteBuf buf = newBuffer(direct);

        try {
            final TProtocol protocol = newProtocol(buf, BINARY, VALUE.length() - 1);
            protocol.writeString(VALUE);

            assertThatThrownBy(protocol::readBinary)
                    .isInstanceOf(TProtocolException.class)
                    .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                            .isEqualTo(TProtocolException.SIZE_LIMIT));
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

    private static Stream<SerializationFormat> formats() {
        return Stream.of(BINARY, COMPACT);
    }

    private static Stream<Arguments> protocolsAndBuffers() {
        return Stream.of(
                Arguments.of(BINARY, false),
                Arguments.of(BINARY, true),
                Arguments.of(COMPACT, false));
    }

    private static boolean supportsLengthLimits() {
        // Match the check in 'ThriftProtocolFactories'.
        try {
            final Constructor<TBinaryProtocol.Factory> ignored =
                    TBinaryProtocol.Factory.class.getConstructor(boolean.class, boolean.class,
                                                                 long.class, long.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
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
}
