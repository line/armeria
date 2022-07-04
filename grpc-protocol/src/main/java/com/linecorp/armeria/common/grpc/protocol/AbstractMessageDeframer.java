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

package com.linecorp.armeria.common.grpc.protocol;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;

/**
 * A skeletal implementation of gRPC message deframer. See
 * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC Wire Format</a>
 * for more detail on the protocol.
 */
public abstract class AbstractMessageDeframer {

    public static final int NO_MAX_INBOUND_MESSAGE_SIZE = -1;

    private static final String DEBUG_STRING = ArmeriaMessageDeframer.class.getName();

    private static final int HEADER_LENGTH = 5;
    private static final int COMPRESSED_FLAG_MASK = 1;
    private static final int RESERVED_MASK = 0x7E;
    // Valid type is always positive.
    static final int UNINITIALIZED_TYPE = -1;

    private final int maxMessageLength;

    private int currentType = UNINITIALIZED_TYPE;
    private int requiredLength = HEADER_LENGTH;

    @Nullable
    private Decompressor decompressor;

    /**
     * Creates a new instance with the specified {@code maxMessageLength}.
     */
    protected AbstractMessageDeframer(int maxMessageLength) {
        this.maxMessageLength = maxMessageLength > 0 ? maxMessageLength : Integer.MAX_VALUE;
    }

    final int requiredLength() {
        return requiredLength;
    }

    boolean isUninitializedType() {
        return currentType == UNINITIALIZED_TYPE;
    }

    /**
     * Processes the gRPC compression header which is composed of the compression flag and the outer
     * frame length.
     */
    protected final void readHeader(StreamDecoderInput in) {
        final int type = in.readUnsignedByte();
        if ((type & RESERVED_MASK) != 0) {
            throw new ArmeriaStatusException(
                    StatusCodes.INTERNAL,
                    DEBUG_STRING + ": Frame header malformed: reserved bits not zero");
        }

        // Update the required length to include the length of the frame.
        requiredLength = in.readInt();
        if (requiredLength < 0 || requiredLength > maxMessageLength) {
            throw new ArmeriaStatusException(
                    StatusCodes.RESOURCE_EXHAUSTED,
                    String.format("%s: Frame size %d exceeds maximum: %d. ",
                                  DEBUG_STRING, requiredLength,
                                  maxMessageLength));
        }

        // Store type and continue reading the frame body.
        currentType = type;
    }

    /**
     * Processes the body of the gRPC compression frame. A single compression frame may contain
     * several gRPC messages within it.
     */
    protected final DeframedMessage readBody(StreamDecoderInput in) {
        final ByteBuf buf;
        if (requiredLength == 0) {
            buf = Unpooled.EMPTY_BUFFER;
        } else {
            buf = in.readBytes(requiredLength);
        }
        final boolean isCompressed = (currentType & COMPRESSED_FLAG_MASK) != 0;
        final DeframedMessage msg = isCompressed ? getCompressedBody(buf) : getUncompressedBody(buf);
        // Done with this frame, begin processing the next header.
        currentType = UNINITIALIZED_TYPE;
        requiredLength = HEADER_LENGTH;
        return msg;
    }

    private DeframedMessage getUncompressedBody(ByteBuf buf) {
        return new DeframedMessage(buf, currentType);
    }

    private DeframedMessage getCompressedBody(ByteBuf buf) {
        if (decompressor == null) {
            buf.release();
            throw new ArmeriaStatusException(
                    StatusCodes.INTERNAL,
                    DEBUG_STRING + ": Can't decode compressed frame as compression not configured.");
        }

        try {
            // Enforce the maxMessageSizeBytes limit on the returned stream.
            final InputStream unlimitedStream =
                    decompressor.decompress(new ByteBufInputStream(buf, true));
            return new DeframedMessage(
                    new SizeEnforcingInputStream(unlimitedStream, maxMessageLength, DEBUG_STRING),
                    currentType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the {@link Decompressor} for this deframer.
     */
    protected AbstractMessageDeframer decompressor(@Nullable Decompressor decompressor) {
        this.decompressor = decompressor;
        return this;
    }

    /**
     * An {@link InputStream} that enforces the {@link #maxMessageSize} limit for compressed frames.
     */
    @VisibleForTesting
    static final class SizeEnforcingInputStream extends FilterInputStream {
        private final int maxMessageSize;
        private final String debugString;
        private long maxCount;
        private long count;
        private long mark = -1;

        SizeEnforcingInputStream(InputStream in, int maxMessageSize, String debugString) {
            super(in);
            this.maxMessageSize = maxMessageSize;
            this.debugString = debugString;
        }

        @Override
        public int read() throws IOException {
            final int result = in.read();
            if (result != -1) {
                count++;
            }
            verifySize();
            reportCount();
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            final int result = in.read(b, off, len);
            if (result != -1) {
                count += result;
            }
            verifySize();
            reportCount();
            return result;
        }

        @Override
        public long skip(long n) throws IOException {
            final long result = in.skip(n);
            count += result;
            verifySize();
            reportCount();
            return result;
        }

        @Override
        public synchronized void mark(int readlimit) {
            in.mark(readlimit);
            mark = count;
            // it's okay to mark even if mark isn't supported, as reset won't work
        }

        @Override
        public synchronized void reset() throws IOException {
            if (!in.markSupported()) {
                throw new IOException("Mark not supported");
            }
            if (mark == -1) {
                throw new IOException("Mark not set");
            }

            in.reset();
            count = mark;
        }

        private void reportCount() {
            if (count > maxCount) {
                maxCount = count;
            }
        }

        private void verifySize() {
            if (count > maxMessageSize) {
                throw new ArmeriaStatusException(
                        StatusCodes.RESOURCE_EXHAUSTED,
                        String.format(
                                "%s: Compressed frame exceeds maximum frame size: %d. Bytes read: %d. ",
                                debugString, maxMessageSize, count));
            }
        }
    }
}
