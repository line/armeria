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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.common.thrift.AbstractTByteBufTransport.ReadBytesNotAvailableException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import testing.thrift.main.NameSortService;

class TByteBufTransportContainerTest {

    private final ByteBuf buf = Unpooled.buffer();

    @AfterEach
    void tearDown() {
        buf.release();
    }

    @ParameterizedTest
    @MethodSource("truncatedContainers")
    void truncatedContainer(SerializationFormat format, byte containerType, byte elementType, int size)
            throws Exception {
        final TProtocol protocol = newProtocol(format, 0);
        writeBegin(protocol, containerType, elementType, size);
        buf.writeByte(0);

        // Checking the header must fail before generated code allocates from the declared count.
        assertThatThrownBy(() -> readBegin(protocol, containerType))
                .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                .hasMessage("unexpected end of frame");
    }

    @ParameterizedTest
    @MethodSource("containers")
    void truncatedPayload(SerializationFormat format, byte containerType) throws Exception {
        final TProtocol protocol = newProtocol(format, 0);
        writeBegin(protocol, containerType, TType.DOUBLE, 2);
        writeElement(protocol, containerType, TType.DOUBLE);
        writeElement(protocol, containerType, TType.DOUBLE);
        buf.writerIndex(buf.writerIndex() - 1);

        // The header bytes cannot count as payload, and a map must account for its values too.
        assertThatThrownBy(() -> readBegin(protocol, containerType))
                .isExactlyInstanceOf(ReadBytesNotAvailableException.class)
                .hasMessage("unexpected end of frame");
    }

    @ParameterizedTest
    @MethodSource("containersAndElementTypes")
    void containerAtLimit(SerializationFormat format, byte containerType, byte elementType) throws Exception {
        final TProtocol protocol = newProtocol(format, 2);
        writeBegin(protocol, containerType, elementType, 2);
        writeElement(protocol, containerType, elementType);
        writeElement(protocol, containerType, elementType);

        assertThat(readBegin(protocol, containerType)).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            if (containerType == TType.MAP) {
                assertThat(protocol.readByte()).isZero();
            }

            TProtocolUtil.skip(protocol, elementType);
        }

        assertThat(buf.isReadable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("containers")
    void emptyContainer(SerializationFormat format, byte containerType) throws Exception {
        final TProtocol protocol = newProtocol(format, 1);
        writeBegin(protocol, containerType, TType.STOP, 0);

        assertThat(readBegin(protocol, containerType)).isZero();
        assertThat(buf.isReadable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("containers")
    void containerLengthLimit(SerializationFormat format, byte containerType) throws Exception {
        final TProtocol protocol = newProtocol(format, 1);
        writeBegin(protocol, containerType, TType.BYTE, 2);
        writeElement(protocol, containerType, TType.BYTE);
        writeElement(protocol, containerType, TType.BYTE);

        assertThatThrownBy(() -> readBegin(protocol, containerType))
                .isInstanceOf(TProtocolException.class)
                .hasMessage("Length exceeded max allowed: 2")
                .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                        .isEqualTo(TProtocolException.SIZE_LIMIT));
    }

    @ParameterizedTest
    @ValueSource(bytes = { TType.LIST, TType.SET, TType.MAP })
    void negativeContainerLength_binary(byte containerType) throws Exception {
        final TProtocol protocol = newProtocol(BINARY, 0);
        writeBegin(protocol, containerType, TType.BYTE, -1);

        assertThatThrownBy(() -> readBegin(protocol, containerType))
                .isInstanceOf(TProtocolException.class)
                .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                        .isEqualTo(TProtocolException.NEGATIVE_SIZE));
    }

    @ParameterizedTest
    @ValueSource(bytes = { TType.LIST, TType.SET, TType.MAP })
    void negativeContainerLength_compact(byte containerType) {
        final TProtocol protocol = newProtocol(COMPACT, 0);
        if (containerType != TType.MAP) {
            buf.writeByte(0xf3); // Extended count, BYTE elements.
        }
        buf.writeBytes(new byte[] { -1, -1, -1, -1, 0x0f }); // -1 as a compact varint.
        if (containerType == TType.MAP) {
            buf.writeByte(0x33); // BYTE keys and values.
        }

        assertThatThrownBy(() -> readBegin(protocol, containerType))
                .isInstanceOf(TProtocolException.class)
                .satisfies(cause -> assertThat(((TProtocolException) cause).getType())
                        .isEqualTo(TProtocolException.NEGATIVE_SIZE));
    }

    @ParameterizedTest
    @MethodSource("formats")
    void generatedListRejectedBeforeAllocation(SerializationFormat format) throws Exception {
        final TProtocol protocol = newProtocol(format, 0);
        protocol.writeStructBegin(new TStruct("sort_args"));
        protocol.writeFieldBegin(new TField("names", TType.LIST, (short) 1));
        protocol.writeListBegin(new TList(TType.STRUCT, 1024));

        final NameSortService.sort_args args = new NameSortService.sort_args();
        assertThatThrownBy(() -> args.read(protocol))
                .isExactlyInstanceOf(ReadBytesNotAvailableException.class);
        assertThat(args.getNames()).isNull();
    }

    private static Stream<SerializationFormat> formats() {
        return Stream.of(BINARY, COMPACT);
    }

    private static Stream<Arguments> containers() {
        return Stream.of(
                Arguments.of(BINARY, TType.LIST),
                Arguments.of(BINARY, TType.SET),
                Arguments.of(BINARY, TType.MAP),
                Arguments.of(COMPACT, TType.LIST),
                Arguments.of(COMPACT, TType.SET),
                Arguments.of(COMPACT, TType.MAP));
    }

    private static Stream<Arguments> containersAndElementTypes() {
        final Stream.Builder<Arguments> arguments = Stream.builder();

        for (SerializationFormat format : ImmutableList.of(BINARY, COMPACT)) {
            for (byte elementType : ImmutableList.of(
                    TType.BOOL, TType.BYTE, TType.DOUBLE, TType.I16, TType.I32, TType.I64,
                    TType.STRING, TType.STRUCT, TType.MAP, TType.SET, TType.LIST)) {
                arguments.add(Arguments.of(format, TType.LIST, elementType));
                arguments.add(Arguments.of(format, TType.SET, elementType));
                arguments.add(Arguments.of(format, TType.MAP, elementType));
            }
        }

        return arguments.build();
    }

    private static Stream<Arguments> truncatedContainers() {
        final Stream.Builder<Arguments> arguments = Stream.builder();

        for (SerializationFormat format : ImmutableList.of(BINARY, COMPACT)) {
            for (byte elementType : ImmutableList.of(TType.BYTE, TType.DOUBLE, TType.STRING, TType.STRUCT)) {
                arguments.add(Arguments.of(format, TType.LIST, elementType, 4 * 1024 * 1024));
                arguments.add(Arguments.of(format, TType.LIST, elementType, Integer.MAX_VALUE));
                arguments.add(Arguments.of(format, TType.SET, elementType, 4 * 1024 * 1024));
                arguments.add(Arguments.of(format, TType.SET, elementType, Integer.MAX_VALUE));
                arguments.add(Arguments.of(format, TType.MAP, elementType, 4 * 1024 * 1024));
                arguments.add(Arguments.of(format, TType.MAP, elementType, Integer.MAX_VALUE));
            }
        }

        return arguments.build();
    }

    private TProtocol newProtocol(SerializationFormat format, int maxContainerLength) {
        return ThriftSerializationFormats.protocolFactory(format, 0, maxContainerLength)
                                         .getProtocol(new TByteBufTransport(buf));
    }

    private static void writeBegin(TProtocol protocol, byte containerType, byte elementType, int size)
            throws TException {
        switch (containerType) {
            case TType.LIST:
                protocol.writeListBegin(new TList(elementType, size));
                break;
            case TType.SET:
                protocol.writeSetBegin(new TSet(elementType, size));
                break;
            case TType.MAP:
                protocol.writeMapBegin(new TMap(TType.BYTE, elementType, size));
                break;
            default:
                throw new AssertionError(containerType);
        }
    }

    private static int readBegin(TProtocol protocol, byte containerType) throws TException {
        switch (containerType) {
            case TType.LIST:
                return protocol.readListBegin().size;
            case TType.SET:
                return protocol.readSetBegin().size;
            case TType.MAP:
                return protocol.readMapBegin().size;
            default:
                throw new AssertionError(containerType);
        }
    }

    private static void writeElement(TProtocol protocol, byte containerType, byte type) throws TException {
        if (containerType == TType.MAP) {
            protocol.writeByte((byte) 0);
        }

        switch (type) {
            case TType.BOOL:
                protocol.writeBool(false);
                break;
            case TType.BYTE:
                protocol.writeByte((byte) 0);
                break;
            case TType.DOUBLE:
                protocol.writeDouble(0);
                break;
            case TType.I16:
                protocol.writeI16((short) 0);
                break;
            case TType.I32:
                protocol.writeI32(0);
                break;
            case TType.I64:
                protocol.writeI64(0);
                break;
            case TType.STRING:
                protocol.writeString("");
                break;
            case TType.STRUCT:
                protocol.writeStructBegin(new TStruct());
                protocol.writeFieldStop();
                protocol.writeStructEnd();
                break;
            case TType.MAP:
                protocol.writeMapBegin(new TMap(TType.STOP, TType.STOP, 0));
                protocol.writeMapEnd();
                break;
            case TType.SET:
                protocol.writeSetBegin(new TSet(TType.STOP, 0));
                protocol.writeSetEnd();
                break;
            case TType.LIST:
                protocol.writeListBegin(new TList(TType.STOP, 0));
                protocol.writeListEnd();
                break;
            default:
                throw new AssertionError(type);
        }
    }
}
