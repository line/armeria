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

package com.linecorp.armeria.common.thrift;

import org.apache.thrift.protocol.TType;

import com.linecorp.armeria.common.SerializationFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class ThriftMessageTestUtil {

    // Copied from TCompactProtocol
    private static final byte PROTOCOL_ID = (byte) 0x82;
    private static final byte VERSION = 1;
    private static final byte VERSION_MASK = 0x1f; // 0001 1111
    private static final byte TYPE_MASK = (byte) 0xE0; // 1110 0000
    private static final int TYPE_SHIFT_AMOUNT = 5;

    public static ByteBuf newMessage(SerializationFormat serializationFormat, int length, byte[] data) {
        if (serializationFormat == ThriftSerializationFormats.BINARY) {
            return newBinaryMessage(length, data);
        } else if (serializationFormat == ThriftSerializationFormats.COMPACT) {
            return newCompactMessage(length, data);
        } else {
            throw new IllegalArgumentException("Unsupported serialization format");
        }
    }

    public static ByteBuf newBinaryMessage(int length, byte[] data) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeInt(length);
        buf.writeBytes(data);
        return buf;
    }

    public static ByteBuf newCompactMessage(int length, byte[] data) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeByte(PROTOCOL_ID);
        buf.writeByte((VERSION & VERSION_MASK) | ((TType.STRUCT << TYPE_SHIFT_AMOUNT) & TYPE_MASK));
        // message.seqid
        writeVarint32(buf, 0);
        // Set a spurious message size.
        // message.name
        writeVarint32(buf, length);
        buf.writeBytes(data);
        return buf;
    }

    /**
     * Forked from TCompactProtocol.writeVarint32().
     */
    private static void writeVarint32(ByteBuf buf, int n) {
        final byte[] i32buf = new byte[5];
        int idx = 0;
        while (true) {
            if ((n & ~0x7F) == 0) {
                i32buf[idx++] = (byte) n;
                // writeByteDirect((byte)n);
                break;
                // return;
            } else {
                i32buf[idx++] = (byte) ((n & 0x7F) | 0x80);
                // writeByteDirect((byte)((n & 0x7F) | 0x80));
                n >>>= 7;
            }
        }
        buf.writeBytes(i32buf, 0, idx);
    }

    private ThriftMessageTestUtil() {}
}
