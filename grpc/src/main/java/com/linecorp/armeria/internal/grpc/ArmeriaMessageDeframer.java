/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;

import io.grpc.Codec;
import io.grpc.Codec.Identity;
import io.grpc.Decompressor;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;

/**
 * A deframer of messages transported in the gRPC wire format. See
 * <a href="https://grpc.io/docs/guides/wire.html">gRPC Wire Protocol</a> for more detail on the protocol.
 *
 * <p>The logic has been mostly copied from {@code io.grpc.internal.MessageDeframer}, while removing the buffer
 * abstraction in favor of using {@link ByteBuf} directly, and allowing the delivery of uncompressed frames as
 * a {@link ByteBuf} to optimize message parsing.
 */
public class ArmeriaMessageDeframer implements AutoCloseable {

    private static final String DEBUG_STRING = ArmeriaMessageDeframer.class.getName();

    private static final int HEADER_LENGTH = 5;
    private static final int COMPRESSED_FLAG_MASK = 1;
    private static final int RESERVED_MASK = 0xFE;

    /**
     * A deframed message. For uncompressed messages, we have the entire buffer available and return it
     * as is in {@code buf} to optimize parsing. For compressed messages, we will parse incrementally
     * and thus return a {@link InputStream} in {@code stream}.
     */
    public static class ByteBufOrStream {
        @Nullable
        private final ByteBuf buf;
        @Nullable
        private final InputStream stream;

        @VisibleForTesting
        public ByteBufOrStream(ByteBuf buf) {
            this(requireNonNull(buf, "buf"), null);
        }

        @VisibleForTesting
        public ByteBufOrStream(InputStream stream) {
            this(null, requireNonNull(stream, "stream"));
        }

        private ByteBufOrStream(@Nullable ByteBuf buf, @Nullable InputStream stream) {
            this.buf = buf;
            this.stream = stream;
        }

        @Nullable
        public ByteBuf buf() {
            return buf;
        }

        @Nullable
        public InputStream stream() {
            return stream;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ByteBufOrStream that = (ByteBufOrStream) o;

            return Objects.equals(buf, that.buf) && Objects.equals(stream, that.stream);
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
        void messageRead(ByteBufOrStream message);

        /**
         * Called when the stream is complete and all messages have been successfully delivered.
         */
        void endOfStream();
    }

    private enum State {
        HEADER, BODY
    }

    private final Listener listener;
    private final int maxMessageSizeBytes;
    private final ByteBufAllocator alloc;

    private State state = State.HEADER;
    private int requiredLength = HEADER_LENGTH;
    private Decompressor decompressor = Identity.NONE;

    private boolean compressedFlag;
    private boolean endOfStream;
    private CompositeByteBuf nextFrame;
    private CompositeByteBuf unprocessed;
    private long pendingDeliveries;
    private boolean deliveryStalled = true;
    private boolean inDelivery;
    private boolean startedDeframing;

    public ArmeriaMessageDeframer(Listener listener,
                                  int maxMessageSizeBytes,
                                  ByteBufAllocator alloc) {
        this.listener = requireNonNull(listener, "listener");
        this.maxMessageSizeBytes = maxMessageSizeBytes;
        this.alloc = requireNonNull(alloc, "alloc");

        unprocessed = alloc.compositeBuffer();
    }

    /**
     * Requests up to the given number of messages from the call to be delivered to
     * {@link Listener#messageRead(ByteBufOrStream)}. No additional messages will be delivered.
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
        return deliveryStalled;
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

        if (!data.isEmpty()) {
            ByteBuf buf = alloc.buffer(data.length());
            buf.writeBytes(data.array(), data.offset(), data.length());
            unprocessed.addComponent(true, buf);
        }

        // Indicate that all of the data for this stream has been received.
        this.endOfStream = endOfStream;
        deliver();
    }

    /**
     * Closes this deframer and frees any resources. After this method is called, additional
     * calls will have no effect.
     */
    @Override
    public void close() {
        try {
            if (unprocessed != null) {
                unprocessed.release();
            }
            if (nextFrame != null) {
                nextFrame.release();
            }
        } finally {
            unprocessed = null;
            nextFrame = null;
        }
    }

    /**
     * Indicates whether or not this deframer has been closed.
     */
    public boolean isClosed() {
        return unprocessed == null;
    }

    public ArmeriaMessageDeframer decompressor(Decompressor decompressor) {
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
            while (pendingDeliveries > 0 && readRequiredBytes()) {
                switch (state) {
                    case HEADER:
                        processHeader();
                        break;
                    case BODY:
                        // Read the body and deliver the message.
                        processBody();

                        // Since we've delivered a message, decrement the number of pending
                        // deliveries remaining.
                        pendingDeliveries--;
                        break;
                    default:
                        throw new IllegalStateException("Invalid state: " + state);
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
            boolean stalled = !unprocessed.isReadable();

            if (endOfStream && stalled) {
                boolean havePartialMessage = nextFrame != null && nextFrame.isReadable();
                if (!havePartialMessage) {
                    listener.endOfStream();
                    deliveryStalled = false;
                    return;
                } else {
                    // We've received the entire stream and have data available but we don't have
                    // enough to read the next frame ... this is bad.
                    throw Status.INTERNAL.withDescription(
                            DEBUG_STRING + ": Encountered end-of-stream mid-frame").asRuntimeException();
                }
            }
            deliveryStalled = stalled;
        } finally {
            inDelivery = false;
        }
    }

    /**
     * Attempts to read the required bytes into nextFrame.
     *
     * @return {@code true} if all of the required bytes have been read.
     */
    private boolean readRequiredBytes() {
        if (nextFrame == null) {
            nextFrame = alloc.compositeBuffer();
        }

        // Read until the buffer contains all the required bytes.
        int missingBytes;
        while ((missingBytes = requiredLength - nextFrame.readableBytes()) > 0) {
            int numUnprocessedBytes = unprocessed.readableBytes();
            if (numUnprocessedBytes == 0) {
                // No more data is available.
                return false;
            }
            int toRead = Math.min(missingBytes, numUnprocessedBytes);
            if (toRead > 0) {
                nextFrame.addComponent(true, unprocessed.readBytes(toRead));
                unprocessed.discardReadComponents();
            }
        }
        return true;
    }

    /**
     * Processes the gRPC compression header which is composed of the compression flag and the outer
     * frame length.
     */
    private void processHeader() {
        int type = nextFrame.readUnsignedByte();
        if ((type & RESERVED_MASK) != 0) {
            throw Status.INTERNAL.withDescription(
                    DEBUG_STRING + ": Frame header malformed: reserved bits not zero")
                                 .asRuntimeException();
        }
        compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

        // Update the required length to include the length of the frame.
        requiredLength = nextFrame.readInt();
        if (requiredLength < 0 || requiredLength > maxMessageSizeBytes) {
            throw Status.RESOURCE_EXHAUSTED.withDescription(
                    String.format("%s: Frame size %d exceeds maximum: %d. ",
                                  DEBUG_STRING, requiredLength,
                                  maxMessageSizeBytes)).asRuntimeException();
        }

        // Continue reading the frame body.
        state = State.BODY;
    }

    /**
     * Processes the body of the gRPC compression frame. A single compression frame may contain
     * several gRPC messages within it.
     */
    private void processBody() {
        ByteBufOrStream msg = compressedFlag ? getCompressedBody() : getUncompressedBody();
        nextFrame = null;
        listener.messageRead(msg);

        // Done with this frame, begin processing the next header.
        state = State.HEADER;
        requiredLength = HEADER_LENGTH;
    }

    private ByteBufOrStream getUncompressedBody() {
        return new ByteBufOrStream(nextFrame.consolidate());
    }

    private ByteBufOrStream getCompressedBody() {
        if (decompressor == Codec.Identity.NONE) {
            throw Status.INTERNAL.withDescription(
                    DEBUG_STRING + ": Can't decode compressed frame as compression not configured.")
                                 .asRuntimeException();
        }

        try {
            // Enforce the maxMessageSizeBytes limit on the returned stream.
            InputStream unlimitedStream =
                    decompressor.decompress(new ByteBufInputStream(nextFrame, true));
            return new ByteBufOrStream(
                    new SizeEnforcingInputStream(unlimitedStream, maxMessageSizeBytes, DEBUG_STRING));
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
            int result = in.read();
            if (result != -1) {
                count++;
            }
            verifySize();
            reportCount();
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int result = in.read(b, off, len);
            if (result != -1) {
                count += result;
            }
            verifySize();
            reportCount();
            return result;
        }

        @Override
        public long skip(long n) throws IOException {
            long result = in.skip(n);
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
                throw Status.INTERNAL.withDescription(String.format(
                        "%s: Compressed frame exceeds maximum frame size: %d. Bytes read: %d. ",
                        debugString, maxMessageSize, count)).asRuntimeException();
            }
        }
    }
}
