/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * An {@link OutputStream} that uses {@link ByteBuffer} to accumulate stream data before flushing to the
 * designated {@link Consumer}.
 */
public final class ByteBufferBackedOutputStream extends OutputStream {

    private final ByteBuffer buffer;
    private final Consumer<ByteBuffer> flushConsumer;
    private boolean closed;
    private boolean flushed;

    public ByteBufferBackedOutputStream(int capacity, Consumer<ByteBuffer> flushConsumer) {
        checkArgument(capacity > 0, "buffer capacity: %s (expected: > 0)", capacity);
        buffer = ByteBuffer.allocate(capacity);
        this.flushConsumer = requireNonNull(flushConsumer, "bufferConsumer");
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) {
            throw new IllegalStateException("Already closed");
        }
        if (buffer.hasRemaining()) {
            buffer.put((byte) b);
        } else {
            flush();
            write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IllegalStateException("Already closed");
        }
        final int remaining = buffer.remaining();
        if (remaining >= len) {
            buffer.put(b, off, len);
        } else {
            buffer.put(b, off, remaining);
            flush();
            write(b, off + remaining, len - remaining);
        }
    }

    @Override
    public void flush() throws IOException {
        buffer.flip();
        flushConsumer.accept(buffer);
        buffer.clear();
        flushed = true;
    }

    @Override
    public void close() throws IOException {
        flush();
        closed = true;
    }

    /**
     * Resets the the underlying buffer and the flush indicator.
     */
    public void reset() {
        buffer.clear();
        flushed = false;
    }

    /**
     * Indicates whether or not the underlying buffer has been flushed.
     */
    public boolean hasFlushed() {
        return flushed;
    }

    /**
     * Indicates whether of the underlying buffer has any content written.
     */
    public boolean hasWritten() {
        return buffer.position() > 0;
    }

    /**
     * Returns a number of bytes written of the underlying buffer so far.
     */
    public int written() {
        return buffer.position();
    }

    /**
     * Returns current content of the underlying buffer and closes the stream.
     * This is a terminating methods that could serve an alternative to {@link #flush()} and {@link #close()}.
     * It allows closing the {@link OutputStream} without flushing the content.
     */
    public ByteBuffer dumpWrittenAndClose() {
        closed = true;
        buffer.flip();
        return buffer;
    }
}
