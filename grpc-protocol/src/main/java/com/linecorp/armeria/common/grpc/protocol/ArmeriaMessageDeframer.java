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
/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.common.grpc.protocol;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.HttpDeframerHandler;
import com.linecorp.armeria.common.stream.HttpDeframerInput;
import com.linecorp.armeria.common.stream.HttpDeframerOutput;
import com.linecorp.armeria.internal.common.grpc.protocol.Base64Decoder;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;

/**
 * A deframer of messages transported in the gRPC wire format. See
 * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC Wire Format</a>
 * for more detail on the protocol.
 *
 * <p>The logic has been mostly copied from {@code io.grpc.internal.MessageDeframer}, while removing the buffer
 * abstraction in favor of using {@link ByteBuf} directly, and allowing the delivery of uncompressed frames as
 * a {@link ByteBuf} to optimize message parsing.
 */
@UnstableApi
public class ArmeriaMessageDeframer implements HttpDeframerHandler<DeframedMessage> {

    public static final int NO_MAX_INBOUND_MESSAGE_SIZE = -1;

    private static final String DEBUG_STRING = ArmeriaMessageDeframer.class.getName();

    private static final int HEADER_LENGTH = 5;
    private static final int COMPRESSED_FLAG_MASK = 1;
    private static final int RESERVED_MASK = 0x7E;
    // Valid type is always positive.
    private static final int UNINITIALIED_TYPE = -1;

    private final int maxMessageSizeBytes;

    private int currentType = UNINITIALIED_TYPE;
    private int requiredLength = HEADER_LENGTH;
    private boolean startedDeframing;

    @Nullable
    private Decompressor decompressor;

    /**
     * Construct an {@link ArmeriaMessageDeframer} for reading messages out of a gRPC request or response.
     */
    public ArmeriaMessageDeframer(int maxMessageSizeBytes) {
        this.maxMessageSizeBytes = maxMessageSizeBytes > 0 ? maxMessageSizeBytes : Integer.MAX_VALUE;
    }

    /**
     * Returns a newly-created {@link HttpDeframer} using this {@link HttpDeframerHandler}.
     */
    public final HttpDeframer<DeframedMessage> newHttpDeframer(ByteBufAllocator alloc) {
        return newHttpDeframer(alloc, false);
    }

    /**
     * Returns a newly-created {@link HttpDeframer} using this {@link HttpDeframerHandler}.
     * If {@code decodeBase64} is set to true, a base64-encoded {@link ByteBuf} is decoded before deframing.
     */
    public final HttpDeframer<DeframedMessage> newHttpDeframer(ByteBufAllocator alloc, boolean decodeBase64) {
        final Base64Decoder base64Decoder;
        if (decodeBase64) {
            base64Decoder = new Base64Decoder(alloc);
        } else {
            base64Decoder = null;
        }
        return new HttpDeframer<>(this, alloc, data -> {
            if (base64Decoder != null) {
                return base64Decoder.decode(data.byteBuf());
            } else {
                return data.byteBuf();
            }
        });
    }

    @Override
    public void process(HttpDeframerInput in, HttpDeframerOutput<DeframedMessage> out) {
        startedDeframing = true;
        int readableBytes = in.readableBytes();
        while (readableBytes >= requiredLength) {
            final int length = requiredLength;
            if (currentType == UNINITIALIED_TYPE) {
                readHeader(in);
            } else {
                out.add(readBody(in));
            }
            readableBytes -= length;
        }
    }

    /**
     * Processes the gRPC compression header which is composed of the compression flag and the outer
     * frame length.
     */
    private void readHeader(HttpDeframerInput in) {
        final int type = in.readUnsignedByte();
        if ((type & RESERVED_MASK) != 0) {
            throw new ArmeriaStatusException(
                    StatusCodes.INTERNAL,
                    DEBUG_STRING + ": Frame header malformed: reserved bits not zero");
        }

        // Update the required length to include the length of the frame.
        requiredLength = in.readInt();
        if (requiredLength < 0 || requiredLength > maxMessageSizeBytes) {
            throw new ArmeriaStatusException(
                    StatusCodes.RESOURCE_EXHAUSTED,
                    String.format("%s: Frame size %d exceeds maximum: %d. ",
                                  DEBUG_STRING, requiredLength,
                                  maxMessageSizeBytes));
        }

        // Store type and continue reading the frame body.
        currentType = type;
    }

    /**
     * Processes the body of the gRPC compression frame. A single compression frame may contain
     * several gRPC messages within it.
     */
    private DeframedMessage readBody(HttpDeframerInput in) {
        final ByteBuf buf;
        if (requiredLength == 0) {
            buf = Unpooled.EMPTY_BUFFER;
        } else {
            buf = in.readBytes(requiredLength);
        }
        final boolean isCompressed = (currentType & COMPRESSED_FLAG_MASK) != 0;
        final DeframedMessage msg = isCompressed ? getCompressedBody(buf) : getUncompressedBody(buf);
        // Done with this frame, begin processing the next header.
        currentType = UNINITIALIED_TYPE;
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
                    new SizeEnforcingInputStream(unlimitedStream, maxMessageSizeBytes, DEBUG_STRING),
                    currentType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the {@link Decompressor} for this deframer.
     */
    public ArmeriaMessageDeframer decompressor(@Nullable Decompressor decompressor) {
        checkState(!startedDeframing,
                   "Deframing has already started, cannot change decompressor mid-stream.");
        this.decompressor = decompressor;
        return this;
    }

    /**
     * A deframed message. For uncompressed messages, we have the entire buffer available and return it
     * as is in {@code buf} to optimize parsing. For compressed messages, we will parse incrementally
     * and thus return a {@link InputStream} in {@code stream}.
     */
    @UnstableApi
    public static final class DeframedMessage {
        private final int type;

        @Nullable
        private final ByteBuf buf;
        @Nullable
        private final InputStream stream;

        /**
         * Creates a new instance with the specified {@link ByteBuf} and {@code type}.
         */
        @VisibleForTesting
        public DeframedMessage(ByteBuf buf, int type) {
            this(requireNonNull(buf, "buf"), null, type);
        }

        /**
         * Creates a new instance with the specified {@link InputStream} and {@code type}.
         */
        @VisibleForTesting
        public DeframedMessage(InputStream stream, int type) {
            this(null, requireNonNull(stream, "stream"), type);
        }

        private DeframedMessage(@Nullable ByteBuf buf, @Nullable InputStream stream, int type) {
            this.buf = buf;
            this.stream = stream;
            this.type = type;
        }

        /**
         * Returns the {@link ByteBuf}.
         *
         * @return the {@link ByteBuf}, or {@code null} if not created with
         *         {@link #DeframedMessage(ByteBuf, int)}.
         */
        @Nullable
        public ByteBuf buf() {
            return buf;
        }

        /**
         * Returns the {@link InputStream}.
         *
         * @return the {@link InputStream}, or {@code null} if not created with
         *         {@link #DeframedMessage(InputStream, int)}.
         */
        @Nullable
        public InputStream stream() {
            return stream;
        }

        /**
         * Returns the type.
         */
        public int type() {
            return type;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DeframedMessage)) {
                return false;
            }

            final DeframedMessage that = (DeframedMessage) o;

            return type == that.type && Objects.equals(buf, that.buf) && Objects.equals(stream, that.stream);
        }

        @Override
        public int hashCode() {
            return Objects.hash(buf, stream);
        }
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
