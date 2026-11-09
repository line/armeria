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

package com.linecorp.armeria.internal.common.thrift;

import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TTransportException;

import io.netty.buffer.ByteBuf;

public final class TByteBufTransport extends AbstractTByteBufTransport {

    public TByteBufTransport(ByteBuf buf) {
        super(buf);
    }

    @Override
    public TBinaryProtocol newBinaryProtocol(int maxStringLength, int maxContainerLength) {
        return new CheckedBinaryProtocol(maxStringLength, maxContainerLength);
    }

    @Override
    public TCompactProtocol newCompactProtocol(int maxStringLength, int maxContainerLength) {
        return new CheckedCompactProtocol(maxStringLength, maxContainerLength);
    }

    private void checkContainerReadLength(int size, int maxContainerLength, int minElementSize)
            throws TException {
        if (size < 0) {
            throw new TProtocolException(TProtocolException.NEGATIVE_SIZE, "Negative length: " + size);
        }

        checkLength(size, maxContainerLength);
        checkReadBytesAvailable((long) size * minElementSize);
    }

    private final class CheckedBinaryProtocol extends TBinaryProtocol {

        private final int maxStringLength;
        private final int maxContainerLength;

        CheckedBinaryProtocol(int maxStringLength, int maxContainerLength) {
            super(TByteBufTransport.this);
            this.maxStringLength = maxStringLength;
            this.maxContainerLength = maxContainerLength;
        }

        @Override
        public TMap readMapBegin() throws TException {
            final TMap map = super.readMapBegin();
            final int minElementSize = minSerializedSize(map.keyType) + minSerializedSize(map.valueType);
            checkContainerReadLength(map.size, maxContainerLength, minElementSize);
            return map;
        }

        @Override
        public TList readListBegin() throws TException {
            final TList list = super.readListBegin();
            checkContainerReadLength(list.size, maxContainerLength, minSerializedSize(list.elemType));
            return list;
        }

        @Override
        public TSet readSetBegin() throws TException {
            final TSet set = super.readSetBegin();
            checkContainerReadLength(set.size, maxContainerLength, minSerializedSize(set.elemType));
            return set;
        }

        @Override
        public String readString() throws TException {
            // Heap buffer reads can bypass 'readStringBody()'.
            checkBinaryReadLength(maxStringLength);
            return super.readString();
        }

        @Override
        public String readStringBody(int size) throws TException {
            // Also called by the upstream reader for unversioned binary message names.
            checkReadBytesAvailable(size);
            checkLength(size, maxStringLength);
            return super.readStringBody(size);
        }

        @Override
        public ByteBuffer readBinary() throws TException {
            checkBinaryReadLength(maxStringLength);
            return super.readBinary();
        }

        private int minSerializedSize(byte type) throws TTransportException {
            switch (type) {
                case TType.STOP:
                case TType.VOID:
                case TType.BOOL:
                case TType.BYTE:
                // An empty struct also contains a STOP byte.
                case TType.STRUCT:
                    return 1;

                case TType.DOUBLE:
                case TType.I64:
                    return Long.BYTES;

                case TType.I16:
                    return Short.BYTES;

                case TType.I32:
                case TType.STRING:
                case TType.MAP:
                case TType.SET:
                case TType.LIST:
                    // Binary strings and containers include a four-byte length prefix.
                    return Integer.BYTES;

                default:
                    throw new TTransportException(TTransportException.UNKNOWN, "unrecognized type code");
            }
        }
    }

    private final class CheckedCompactProtocol extends TCompactProtocol {

        private final int maxStringLength;
        private final int maxContainerLength;

        CheckedCompactProtocol(int maxStringLength, int maxContainerLength) {
            super(TByteBufTransport.this);
            this.maxStringLength = maxStringLength;
            this.maxContainerLength = maxContainerLength;
        }

        @Override
        public TMap readMapBegin() throws TException {
            final TMap map = super.readMapBegin();
            final int minElementSize = minSerializedSize(map.keyType) + minSerializedSize(map.valueType);
            checkContainerReadLength(map.size, maxContainerLength, minElementSize);
            return map;
        }

        // Also handles sets, since 'TCompactProtocol.readSetBegin()' delegates to this method.
        @Override
        public TList readListBegin() throws TException {
            final TList list = super.readListBegin();
            checkContainerReadLength(list.size, maxContainerLength, minSerializedSize(list.elemType));
            return list;
        }

        @Override
        public String readString() throws TException {
            // Also called by the upstream reader for compact message names.
            checkCompactReadLength(maxStringLength);
            return super.readString();
        }

        @Override
        public ByteBuffer readBinary() throws TException {
            checkCompactReadLength(maxStringLength);
            return super.readBinary();
        }

        private int minSerializedSize(byte type) {
            // TCompactProtocol validates type codes before returning container headers.
            return type == TType.DOUBLE ? Double.BYTES : 1;
        }
    }
}
