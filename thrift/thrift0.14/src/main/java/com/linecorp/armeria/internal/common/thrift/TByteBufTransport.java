/*
 * Copyright 2021 LY Corporation
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

import java.nio.ByteBuffer;

import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.transport.TTransportException;

import io.netty.buffer.ByteBuf;

public final class TByteBufTransport extends AbstractTByteBufTransport {

    private final TConfiguration configuration = new TConfiguration();

    public TByteBufTransport(ByteBuf buf) {
        super(buf);
    }

    @Override
    public TConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void updateKnownMessageSize(long size) throws TTransportException {
        // This method is not called by the 'TProtocol's provided by Armeria
    }

    @Override
    public TBinaryProtocol newBinaryProtocol(int maxStringLength, int maxContainerLength) {
        return new CheckedBinaryProtocol(maxStringLength, maxContainerLength);
    }

    @Override
    public TCompactProtocol newCompactProtocol(int maxStringLength, int maxContainerLength) {
        return new CheckedCompactProtocol(maxStringLength, maxContainerLength);
    }

    private final class CheckedBinaryProtocol extends TBinaryProtocol {

        private final int maxStringLength;

        CheckedBinaryProtocol(int maxStringLength, int maxContainerLength) {
            super(TByteBufTransport.this, maxStringLength, maxContainerLength, false, true);
            this.maxStringLength = maxStringLength;
        }

        @Override
        public int getMinSerializedSize(byte type) throws TTransportException {
            // Older Thrift versions treat empty structs as zero bytes, omitting the STOP byte.
            return Math.max(1, super.getMinSerializedSize(type));
        }

        @Override
        protected void checkReadBytesAvailable(TList list) throws TException {
            // Avoid overflow when multiplying the count.
            final long minBytes = (long) list.size * getMinSerializedSize(list.elemType);
            getTransport().checkReadBytesAvailable(minBytes);
        }

        @Override
        protected void checkReadBytesAvailable(TSet set) throws TException {
            final long minBytes = (long) set.size * getMinSerializedSize(set.elemType);
            getTransport().checkReadBytesAvailable(minBytes);
        }

        @Override
        public String readString() throws TException {
            // Thrift 0.14.0~0.23.0 skips the length limit when reading strings directly from a heap buffer.
            checkBinaryReadLength(maxStringLength);
            return super.readString();
        }
    }

    private final class CheckedCompactProtocol extends TCompactProtocol {

        private final int maxStringLength;

        CheckedCompactProtocol(int maxStringLength, int maxContainerLength) {
            super(TByteBufTransport.this, maxStringLength, maxContainerLength);
            this.maxStringLength = maxStringLength;
        }

        @Override
        public int getMinSerializedSize(byte type) throws TTransportException {
            return Math.max(1, super.getMinSerializedSize(type));
        }

        // Also handles sets, since 'TCompactProtocol.readSetBegin()' delegates to 'readListBegin()'.
        @Override
        protected void checkReadBytesAvailable(TList list) throws TException {
            final long minBytes = (long) list.size * getMinSerializedSize(list.elemType);
            getTransport().checkReadBytesAvailable(minBytes);
        }

        @Override
        public ByteBuffer readBinary() throws TException {
            // Thrift 0.14.0~0.23.0 checks availability, but skips the length limit for binary fields.
            checkCompactReadLength(maxStringLength);
            return super.readBinary();
        }
    }
}
