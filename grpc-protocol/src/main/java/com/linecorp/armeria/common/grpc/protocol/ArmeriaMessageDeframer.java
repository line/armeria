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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.UnstableApi;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
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
public class ArmeriaMessageDeframer implements AutoCloseable {

    private static final String DEBUG_STRING = ArmeriaMessageDeframer.class.getName();

    private static final int HEADER_LENGTH = 5;
    private static final int COMPRESSED_FLAG_MASK = 1;
    private static final int RESERVED_MASK = 0x7E;
    // Valid type is always positive.
    private static final int UNINITIALIED_TYPE = -1;

    /**
     * A deframed message. For uncompressed messages, we have the entire buffer available and return it
     * as is in {@code buf} to optimize parsing. For compressed messages, we will parse incrementally
     * and thus return a {@link InputStream} in {@code stream}.
     */
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
     * A listener of deframing events.
     */
    public interface Listener {

        /**
         * Called to deliver the next complete message. Either {@code message.buf} or {@code message.stream}
         * will be non-null. {@code message.buf} must be released, or {@code message.stream} must be closed by
         * the callee.
         */
        void messageRead(DeframedMessage message);

        /**
         * Called when the stream is complete and all messages have been successfully delivered.
         */
        void endOfStream();
    }

    private final Listener listener;
    private final int maxMessageSizeBytes;
    private final ByteBufAllocator alloc;

    private int currentType = UNINITIALIED_TYPE;

    private int requiredLength = HEADER_LENGTH;
    @Nullable
    private Decompressor decompressor;

    private boolean endOfStream;
    private boolean closeWhenComplete;

    @Nullable
    private Queue<ByteBuf> unprocessed;
    private int unprocessedBytes;

    private long pendingDeliveries;
    private boolean inDelivery;
    private boolean startedDeframing;

    /**
     * Construct an {@link ArmeriaMessageDeframer} for reading messages out of a gRPC request or response.
     */
    public ArmeriaMessageDeframer(Listener listener,
                                  int maxMessageSizeBytes,
                                  ByteBufAllocator alloc) {
        this.listener = requireNonNull(listener, "listener");
        this.maxMessageSizeBytes = maxMessageSizeBytes > 0 ? maxMessageSizeBytes : Integer.MAX_VALUE;
        this.alloc = requireNonNull(alloc, "alloc");

        unprocessed = new ArrayDeque<>();
    }

    /**
     * Requests up to the given number of messages from the call to be delivered to
     * {@link Listener#messageRead(DeframedMessage)}. No additional messages will be delivered.
     *
     * <p>If {@link #close()} has been called, this method will have no effect.
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     */
    public void request(int numMessages) {
        checkArgument(numMessages > 0, "numMessages must be > 0");
        if (isClosed()) {
            return;
        }
        pendingDeliveries += numMessages;
        deliver();
    }

    /**
     * Indicates whether delivery is currently stalled, pending receipt of more data.  This means
     * that no additional data can be delivered to the application.
     */
    public boolean isStalled() {
        return !hasRequiredBytes();
    }

    /**
     * Adds the given data to this deframer and attempts delivery to the listener.
     *
     * @param data the raw data read from the remote endpoint. Must be non-null.
     * @param endOfStream if {@code true}, indicates that {@code data} is the end of the stream from
     *        the remote endpoint.  End of stream should not be used in the event of a transport
     *        error, such as a stream reset.
     * @throws IllegalStateException if {@link #close()} has been called previously or if
     *         this method has previously been called with {@code endOfStream=true}.
     */
    public void deframe(HttpData data, boolean endOfStream) {
        requireNonNull(data, "data");
        checkNotClosed();
        checkState(!this.endOfStream, "Past end of stream");

        startedDeframing = true;

        final int dataLength = data.length();
        if (dataLength != 0) {
            final ByteBuf buf;
            if (data instanceof ByteBufHolder) {
                buf = ((ByteBufHolder) data).content();
            } else {
                buf = Unpooled.wrappedBuffer(data.array());
            }
            assert unprocessed != null;
            unprocessed.add(buf);
            unprocessedBytes += dataLength;
        }

        // Indicate that all of the data for this stream has been received.
        this.endOfStream = endOfStream;
        deliver();
    }

    /** Requests closing this deframer when any messages currently queued have been requested and delivered. */
    public void closeWhenComplete() {
        if (isClosed()) {
            return;
        } else if (isStalled()) {
            close();
        } else {
            closeWhenComplete = true;
        }
    }

    /**
     * Closes this deframer and frees any resources. After this method is called, additional
     * calls will have no effect.
     */
    @Override
    public void close() {
        if (unprocessed != null) {
            try {
                unprocessed.forEach(ByteBuf::release);
            } finally {
                unprocessed = null;
            }

            if (endOfStream) {
                listener.endOfStream();
            }
        }
    }

    /**
     * Indicates whether or not this deframer has been closed.
     */
    public boolean isClosed() {
        return unprocessed == null;
    }

    /**
     * Sets the {@link Decompressor} for this deframer.
     */
    public ArmeriaMessageDeframer decompressor(@Nullable Decompressor decompressor) {
        checkState(!startedDeframing, "Deframing has already started, cannot change decompressor mid-stream.");
        this.decompressor = decompressor;
        return this;
    }

    /**
     * Throws if this deframer has already been closed.
     */
    private void checkNotClosed() {
        checkState(!isClosed(), "MessageDeframer is already closed");
    }

    /**
     * Reads and delivers as many messages to the listener as possible.
     */
    private void deliver() {
        // We can have reentrancy here when using a direct executor, triggered by calls to
        // request more messages. This is safe as we simply loop until pendingDelivers = 0
        if (inDelivery) {
            return;
        }
        inDelivery = true;
        try {
            // Process the uncompressed bytes.
            while (pendingDeliveries > 0 && hasRequiredBytes()) {
                if (currentType == UNINITIALIED_TYPE) {
                    readHeader();
                } else {
                    // Read the body and deliver the message.
                    readBody();

                    // Since we've delivered a message, decrement the number of pending
                    // deliveries remaining.
                    pendingDeliveries--;
                }
            }

            /*
             * We are stalled when there are no more bytes to process. This allows delivering errors as
             * soon as the buffered input has been consumed, independent of whether the application
             * has requested another message.  At this point in the function, either all frames have been
             * delivered, or unprocessed is empty.  If there is a partial message, it will be inside next
             * frame and not in unprocessed.  If there is extra data but no pending deliveries, it will
             * be in unprocessed.
             */
            if (closeWhenComplete && isStalled()) {
                close();
            }
        } finally {
            inDelivery = false;
        }
    }

    private boolean hasRequiredBytes() {
        return unprocessedBytes >= requiredLength;
    }

    /**
     * Processes the gRPC compression header which is composed of the compression flag and the outer
     * frame length.
     */
    private void readHeader() {
        final int type = readUnsignedByte();
        if ((type & RESERVED_MASK) != 0) {
            throw new ArmeriaStatusException(
                    StatusCodes.INTERNAL,
                    DEBUG_STRING + ": Frame header malformed: reserved bits not zero");
        }

        // Update the required length to include the length of the frame.
        requiredLength = readInt();
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

    private int readUnsignedByte() {
        unprocessedBytes--;
        assert unprocessed != null;
        final ByteBuf firstBuf = unprocessed.peek();
        assert firstBuf != null;
        final int value = firstBuf.readUnsignedByte();
        if (!firstBuf.isReadable()) {
            unprocessed.remove().release();
        }
        return value;
    }

    private int readInt() {
        unprocessedBytes -= 4;
        assert unprocessed != null;
        final ByteBuf firstBuf = unprocessed.peek();
        assert firstBuf != null;
        final int firstBufLen = firstBuf.readableBytes();

        if (firstBufLen >= 4) {
            final int value = firstBuf.readInt();
            if (!firstBuf.isReadable()) {
                unprocessed.remove().release();
            }
            return value;
        }

        return readIntSlowPath();
    }

    private int readIntSlowPath() {
        assert unprocessed != null;

        int value = 0;
        for (int i = 4; i > 0; i--) {
            final ByteBuf buf = unprocessed.peek();
            assert buf != null;
            value <<= 8;
            value |= buf.readUnsignedByte();
            if (!buf.isReadable()) {
                unprocessed.remove().release();
            }
        }
        return value;
    }

    /**
     * Processes the body of the gRPC compression frame. A single compression frame may contain
     * several gRPC messages within it.
     */
    private void readBody() {
        final ByteBuf buf = readBytes(requiredLength);
        final boolean isCompressed = (currentType & COMPRESSED_FLAG_MASK) != 0;
        final DeframedMessage msg = isCompressed ? getCompressedBody(buf) : getUncompressedBody(buf);
        listener.messageRead(msg);

        // Done with this frame, begin processing the next header.
        currentType = UNINITIALIED_TYPE;
        requiredLength = HEADER_LENGTH;
    }

    private ByteBuf readBytes(int length) {
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }

        unprocessedBytes -= length;
        assert unprocessed != null;
        final ByteBuf firstBuf = unprocessed.peek();
        assert firstBuf != null;
        final int firstBufLen = firstBuf.readableBytes();

        if (firstBufLen == length) {
            unprocessed.remove();
            return firstBuf;
        }

        if (firstBufLen > length) {
            return firstBuf.readRetainedSlice(length);
        }

        return readBytesMerged(length);
    }

    private ByteBuf readBytesMerged(int length) {
        assert unprocessed != null;

        final ByteBuf merged = alloc.buffer(length);
        for (;;) {
            final ByteBuf buf = unprocessed.peek();
            assert buf != null;

            final int bufLen = buf.readableBytes();
            final int remaining = merged.writableBytes();

            if (bufLen <= remaining) {
                merged.writeBytes(buf);
                unprocessed.remove().release();

                if (bufLen == remaining) {
                    return merged;
                }
            } else {
                merged.writeBytes(buf, remaining);
                return merged;
            }
        }
    }

    private DeframedMessage getUncompressedBody(ByteBuf buf) {
        return new DeframedMessage(buf, currentType);
    }

    private boolean isClosedOrScheduledToClose() {
        return isClosed() || closeWhenComplete;
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
