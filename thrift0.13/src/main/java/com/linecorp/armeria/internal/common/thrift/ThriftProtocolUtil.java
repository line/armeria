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
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TProtocolFactory;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class ThriftProtocolUtil {

    private static final boolean SUPPORT_BINARY_MESSAGE_LENGTH_LIMIT;

    static {
        boolean supportMessageLengthLimit = false;
        final TProtocolFactory binaryFactory =
                ThriftSerializationFormats.protocolFactory(ThriftSerializationFormats.BINARY, 5, 0);
        final ByteBuf buf = Unpooled.buffer();
        // Set a larger length than the message.
        buf.writeInt(6);
        buf.writeBytes("Hello".getBytes());
        final TByteBufTransport transport = new TByteBufTransport(buf);
        final TProtocol protocol = binaryFactory.getProtocol(transport);
        try {
            protocol.readMessageBegin();
        } catch (TException e) {
            if (e instanceof TProtocolException &&
                ((TProtocolException) e).getType() == TProtocolException.SIZE_LIMIT) {
                supportMessageLengthLimit = true;
            }
        }
        SUPPORT_BINARY_MESSAGE_LENGTH_LIMIT = supportMessageLengthLimit;
    }

    /**
     * Optionally validates the length a Thrift's message if the {@link SerializationFormat} is
     * {@link ThriftSerializationFormats#BINARY} and the Thrift runtime does not validate the length for
     * `readMessageBegin()`.
     */
    public static void maybeCheckMessageLength(SerializationFormat serializationFormat, ByteBuf buf,
                                               int maxStringLength) throws TProtocolException {
        if (supportsMessageLengthLimit(serializationFormat) || maxStringLength <= 0) {
            return;
        }

        if (buf.readableBytes() < 4) {
            // Delegate the malformed message to readMessageBegin() to handle it.
            return;
        }

        final int length = buf.getInt(buf.readerIndex());
        if (length < 0) {
            // A negative value means tbinary protocol V1 for which TBinaryProtocol correctly validates
            // the message length before creating an array.
            // https://github.com/apache/thrift/blob/0.9.3/lib/java/src/org/apache/thrift/protocol/TBinaryProtocol.java#L225
            // https://github.com/apache/thrift/blob/0.9.3/lib/java/src/org/apache/thrift/protocol/TBinaryProtocol.java#L358
            return;
        }

        if (length > maxStringLength) {
            // Follow the error format of the upstream so that users get the same error regardless of
            // Thrift versions.
            // https://github.com/apache/thrift/blob/5b158389b01d028e98e59f0ea41c01d625a84242/lib/java/src/main/java/org/apache/thrift/protocol/TBinaryProtocol.java#L442
            throw new TProtocolException(TProtocolException.SIZE_LIMIT,
                                         "Length exceeded max allowed: " + length);
        }
    }

    /**
     * Returns whether the current `libthrift` runtime implementation limits the numbers of bytes to
     * read for `TBinaryProtocol`.
     * Thrift 0.9.x, 0.10.x does not support a correct validation of `readMessageBegin()` for `TBinaryProtocol`.
     * See: <a href="https://github.com/apache/thrift/pull/1398#issuecomment-339360568">
     * THRIFT-4362 check "read length" in readStringBody(int)</a>
     */
    private static boolean supportsMessageLengthLimit(SerializationFormat serializationFormat) {
        if (serializationFormat != ThriftSerializationFormats.BINARY) {
            return true;
        }
        return SUPPORT_BINARY_MESSAGE_LENGTH_LIMIT;
    }

    private ThriftProtocolUtil() {}
}
