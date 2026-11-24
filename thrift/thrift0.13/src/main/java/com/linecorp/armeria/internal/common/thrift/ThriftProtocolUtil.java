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

package com.linecorp.armeria.internal.common.thrift;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.common.thrift.AbstractTByteBufTransport.ReadBytesNotAvailableException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class ThriftProtocolUtil {

    private static final boolean SUPPORTS_BINARY_MESSAGE_LENGTH_LIMIT = supportsBinaryMessageLengthLimit();

    /**
     * Reads a message header, preserving the protocol length limit when the message name is also truncated.
     */
    public static TMessage readMessageBegin(TProtocol protocol, SerializationFormat serializationFormat,
                                           ByteBuf buf, int maxStringLength) throws TException {
        // Older Thrift versions do not limit unversioned binary message names.
        // A negative first integer denotes a versioned header, whose name follows it.
        if (!SUPPORTS_BINARY_MESSAGE_LENGTH_LIMIT &&
            serializationFormat == ThriftSerializationFormats.BINARY &&
            maxStringLength > 0 && buf.readableBytes() >= Integer.BYTES) {
            final int length = buf.getInt(buf.readerIndex());
            if (length > maxStringLength) {
                throw new TProtocolException(TProtocolException.SIZE_LIMIT,
                                             "Length exceeded max allowed: " + length);
            }
        }

        try {
            return protocol.readMessageBegin();
        } catch (ReadBytesNotAvailableException e) {
            // BINARY and COMPACT headers contain one variable-length field: the message name.
            // Thrift checks availability before its length limit, so preserve SIZE_LIMIT for names
            // that exceed both the available bytes and the configured limit.
            if ((serializationFormat == ThriftSerializationFormats.BINARY ||
                 serializationFormat == ThriftSerializationFormats.COMPACT) &&
                maxStringLength > 0 && e.requestedBytes() > maxStringLength) {
                throw new TProtocolException(TProtocolException.SIZE_LIMIT,
                                             "Length exceeded max allowed: " + e.requestedBytes());
            }

            throw e;
        }
    }

    private static boolean supportsBinaryMessageLengthLimit() {
        final ByteBuf buf = Unpooled.buffer();

        try {
            // A complete unversioned header with a two-byte name exceeding the one-byte limit.
            buf.writeInt(2).writeShort(0).writeByte(TMessageType.CALL).writeInt(0);
            final TProtocol protocol = ThriftProtocolFactories
                    .binary(1, 0)
                    .getProtocol(new TByteBufTransport(buf));
            protocol.readMessageBegin();
            return false;
        } catch (TProtocolException e) {
            return e.getType() == TProtocolException.SIZE_LIMIT;
        } catch (TException e) {
            return false;
        } finally {
            buf.release();
        }
    }

    private ThriftProtocolUtil() {}
}
