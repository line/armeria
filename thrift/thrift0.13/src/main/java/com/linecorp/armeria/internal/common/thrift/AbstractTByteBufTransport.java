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

import static java.util.Objects.requireNonNull;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;

abstract class AbstractTByteBufTransport extends TTransport {

    private final ByteBuf buf;

    protected AbstractTByteBufTransport(ByteBuf buf) {
        this.buf = requireNonNull(buf, "buf");
    }

    // Also implements 'TTransport.checkReadBytesAvailable()' in Thrift 0.14.0+.
    public final void checkReadBytesAvailable(long numBytes) throws TTransportException {
        if (numBytes > buf.readableBytes()) {
            throw new ReadBytesNotAvailableException(numBytes);
        }
    }

    @Override
    public void close() {}

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void open() {}

    @Override
    public int read(byte[] buf, int off, int len) {
        final int bytesRemaining = this.buf.readableBytes();
        final int amtToRead = Math.min(len, bytesRemaining);
        if (amtToRead > 0) {
            this.buf.readBytes(buf, off, amtToRead);
        }
        return amtToRead;
    }

    @Override
    public int readAll(byte[] buf, int off, int len) throws TTransportException {
        final int bytesRemaining = this.buf.readableBytes();
        if (len > bytesRemaining) {
            throw new TTransportException("unexpected end of frame");
        }

        this.buf.readBytes(buf, off, len);
        return len;
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        this.buf.writeBytes(buf, off, len);
    }

    @Nullable
    @Override
    public byte[] getBuffer() {
        final ByteBuf buf = this.buf;
        if (!buf.hasArray())  {
            return null;
        } else {
            return buf.array();
        }
    }

    @Override
    public int getBufferPosition() {
        final ByteBuf buf = this.buf;
        if (!buf.hasArray())  {
            return 0;
        } else {
            return buf.arrayOffset() + buf.readerIndex();
        }
    }

    @Override
    public int getBytesRemainingInBuffer() {
        final ByteBuf buf = this.buf;
        if (buf.hasArray()) {
            return buf.readableBytes();
        } else {
            return -1;
        }
    }

    @Override
    public void consumeBuffer(int len) {
        buf.skipBytes(len);
    }

    public TBinaryProtocol newBinaryProtocol(int maxStringLength, int maxContainerLength) {
        // Thrift 0.14.0+ checks field lengths through the transport's 'checkReadBytesAvailable()' hook.
        return new TBinaryProtocol(this, maxStringLength, maxContainerLength, false, true);
    }

    public TCompactProtocol newCompactProtocol(int maxStringLength, int maxContainerLength) {
        return new TCompactProtocol(this, maxStringLength, maxContainerLength);
    }

    // Decodes the length as 'TBinaryProtocol.readI32()' does, without consuming the prefix.
    final void checkBinaryReadLength(int maxStringLength) throws TException {
        if (buf.readableBytes() < Integer.BYTES) {
            return;
        }

        final int size = buf.getInt(buf.readerIndex());

        if (size > buf.readableBytes() - Integer.BYTES) {
            throw new ReadBytesNotAvailableException(size);
        }

        checkLength(size, maxStringLength);
    }

    // Mirrors the private 'TCompactProtocol.readVarint32()' method without consuming the prefix.
    final void checkCompactReadLength(int maxStringLength) throws TException {
        int size = 0;
        int shift = 0;

        for (int i = buf.readerIndex(); i < buf.writerIndex(); i++, shift += 7) {
            final byte b = buf.getByte(i);
            // Preserve upstream handling of varints longer than five bytes.
            size |= (b & 0x7f) << shift;

            if ((b & 0x80) == 0) {
                if (size > buf.writerIndex() - i - 1) {
                    throw new ReadBytesNotAvailableException(size);
                }

                checkLength(size, maxStringLength);
                return;
            }
        }

        throw new ReadBytesNotAvailableException(1);
    }

    static void checkLength(int size, int maxLength) throws TProtocolException {
        if (maxLength > 0 && size > maxLength) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT,
                                         "Length exceeded max allowed: " + size);
        }
    }

    static final class ReadBytesNotAvailableException extends TTransportException {

        private static final long serialVersionUID = 8309037250242214731L;

        private final long requestedBytes;

        ReadBytesNotAvailableException(long requestedBytes) {
            super("unexpected end of frame");
            this.requestedBytes = requestedBytes;
        }

        long requestedBytes() {
            return requestedBytes;
        }
    }
}
