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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.handler.codec.base64.Base64;

/**
 * A framer of messages for transport with the gRPC wire protocol. See
 * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC Wire Format</a>
 * for more detail on the protocol.
 *
 * <p>The logic has mostly been copied from {@code io.grpc.internal.MessageFramer}, while removing the buffer
 * abstraction in favor of using {@link ByteBuf} directly. The code has been vastly simplified due to the lack
 * of support for arbitrary {@link InputStream}s.
 */
@UnstableApi
public class ArmeriaMessageFramer implements AutoCloseable {

    public static final int NO_MAX_OUTBOUND_MESSAGE_SIZE = -1;

    private static final int HEADER_LENGTH = 5;
    private static final byte UNCOMPRESSED = 0;
    private static final byte COMPRESSED = 1;
    @VisibleForTesting
    static final byte UNCOMPRESSED_TRAILERS = (byte) (1 << 7);
    @VisibleForTesting
    static final byte COMPRESSED_TRAILERS = (byte) ((1 << 7) | COMPRESSED);

    private final ByteBufAllocator alloc;
    private final int maxMessageLength;
    private final boolean encodeBase64;

    private boolean messageCompression = true;

    @Nullable
    private Compressor compressor;
    private boolean closed;

    /**
     * Constructs an {@link ArmeriaMessageFramer} to write messages to a gRPC request or response.
     */
    public ArmeriaMessageFramer(ByteBufAllocator alloc, int maxMessageLength, boolean encodeBase64) {
        this.alloc = requireNonNull(alloc, "alloc");
        this.maxMessageLength = maxMessageLength;
        this.encodeBase64 = encodeBase64;
    }

    /**
     * Writes out a payload message.
     *
     * @param message the message to be written out. Ownership is taken by {@link ArmeriaMessageFramer}.
     *
     * @return an {@link HttpData} with the framed payload. Ownership is passed to caller.
     */
    public HttpData writePayload(ByteBuf message) {
        return writePayload(message, false);
    }

    /**
     * Writes out a payload message.
     *
     * @param message the message to be written out. Ownership is taken by {@link ArmeriaMessageFramer}.
     * @param webTrailers tells whether the payload is web trailers
     *
     * @return an {@link HttpData} with the framed payload. Ownership is passed to caller. If the specified
     *         {@code webTrailers} is {@code true}, {@link HttpData#isEndOfStream()} returns {@code true}.
     */
    public HttpData writePayload(ByteBuf message, boolean webTrailers) {
        verifyNotClosed();
        final boolean compressed = messageCompression && compressor != null;
        final int messageLength = message.readableBytes();
        try {
            final ByteBuf buf;
            if (messageLength != 0 && compressed) {
                buf = writeCompressed(message, webTrailers);
            } else {
                buf = writeUncompressed(message, webTrailers);
            }

            final ByteBuf maybeEncodedBuf;
            if (encodeBase64) {
                try {
                    maybeEncodedBuf = Base64.encode(buf, false);
                } finally {
                    buf.release();
                }
                final int length = maybeEncodedBuf.readableBytes();
                if (maxMessageLength >= 0 && length > maxMessageLength) {
                    maybeEncodedBuf.release();
                    throw newMessageTooLargeException(length);
                }
            } else {
                maybeEncodedBuf = buf;
            }

            return HttpData.wrap(maybeEncodedBuf).withEndOfStream(webTrailers);
        } catch (ArmeriaStatusException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // IOException will not be thrown, since sink#deliverFrame doesn't throw.
            throw new ArmeriaStatusException(StatusCodes.INTERNAL, "Failed to frame message", e);
        }
    }

    /**
     * Enables or disables message compression.
     *
     * @param messageCompression whether to enable message compression.
     */
    public void setMessageCompression(boolean messageCompression) {
        this.messageCompression = messageCompression;
    }

    /**
     * Sets the {@link Compressor}.
     */
    public void setCompressor(@Nullable Compressor compressor) {
        this.compressor = compressor;
    }

    private ByteBuf writeCompressed(ByteBuf message, boolean webTrailers) throws IOException {
        assert compressor != null;

        // There are not so much chance that the compressed data is bigger than the original data.
        final ByteBuf compressed = alloc.buffer(message.readableBytes());
        try (OutputStream compressingStream = compressor.compress(new ByteBufOutputStream(compressed))) {
            compressingStream.write(ByteBufUtil.getBytes(message));
        } finally {
            message.release();
        }

        return write(compressed, true, webTrailers);
    }

    private ByteBuf writeUncompressed(ByteBuf message, boolean webTrailers) {
        return write(message, false, webTrailers);
    }

    private ByteBuf write(ByteBuf message, boolean compressed, boolean webTrailers) {
        final int messageLength = message.readableBytes();
        if (maxMessageLength >= 0 && messageLength > maxMessageLength) {
            message.release();
            throw newMessageTooLargeException(messageLength);
        }

        final byte flag;
        if (webTrailers) {
            flag = compressed ? COMPRESSED_TRAILERS : UNCOMPRESSED_TRAILERS;
        } else {
            flag = compressed ? COMPRESSED : UNCOMPRESSED;
        }

        // Here comes some heuristics.
        // TODO(trustin): Consider making this configurable.
        if (messageLength <= 128) {
            // Frame is so small that the cost of composition outweighs.
            try {
                final ByteBuf buf = alloc.buffer(HEADER_LENGTH + messageLength);
                buf.writeByte(flag);
                buf.writeInt(messageLength);
                buf.writeBytes(message);
                return buf;
            } finally {
                message.release();
            }
        }

        // Frame is fairly large that composition might reduce memory footprint.
        return new CompositeByteBuf(alloc, true, 2,
                                    alloc.buffer(HEADER_LENGTH)
                                         .writeByte(flag)
                                         .writeInt(messageLength),
                                    message);
    }

    private ArmeriaStatusException newMessageTooLargeException(int messageLength) {
        return new ArmeriaStatusException(
                StatusCodes.RESOURCE_EXHAUSTED,
                String.format("message too large %d > %d", messageLength,
                              maxMessageLength));
    }

    private void verifyNotClosed() {
        checkState(!isClosed(), "Framer already closed");
    }

    /**
     * Indicates whether or not this framer has been closed via a call to either
     * {@link #close()}.
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
