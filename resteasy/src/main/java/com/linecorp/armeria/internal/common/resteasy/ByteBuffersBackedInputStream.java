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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link InputStream} implementation that exposes dynamically available content
 * of series of the {@link ByteBuffer} data chunks until the EOS flag set for this stream.
 * All {@link InputStream} reading operations will block until next data chunk gets available or
 * EOS flag set for the stream.
 * When the EOS flag set, all the available data will be read fully, but no more
 * new {@link ByteBuffer} data chunks will be permitted.
 */
@UnstableApi
public final class ByteBuffersBackedInputStream extends InputStream {

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.wrap(new byte[] {});

    private final BlockingQueue<ByteBuffer> buffers = new LinkedBlockingQueue<>();
    private final AtomicBoolean eos = new AtomicBoolean(false);
    @Nullable
    private volatile ByteBuffer nextBuffer;
    @Nullable
    private final Duration timeout;
    @Nullable
    private Throwable interruption;

    /**
     * Constructs {@link ByteBuffersBackedInputStream} with a timeout.
     * @param timeout {@link Duration} during which the IO will be blocked expecting new data chunks
     *                or EOS flag to be set.
     */
    public ByteBuffersBackedInputStream(Duration timeout) {
        this.timeout = requireNonNull(timeout, "timeout");
    }

    public ByteBuffersBackedInputStream() {
        timeout = null;
    }

    @Nullable
    private ByteBuffer peekBuffer() {
        final ByteBuffer buffer = nextBuffer;
        return (buffer == null) ? buffers.peek() : buffer;
    }

    @Nullable
    private ByteBuffer buffer() throws InterruptedException, IOException {
        ByteBuffer buffer = nextBuffer;
        if (buffer == null) {
            buffer = takeNext();
        }
        return nextBuffer = buffer;
    }

    @Nullable
    private ByteBuffer next() throws InterruptedException, IOException {
        final ByteBuffer buffer = takeNext();
        return nextBuffer = buffer;
    }

    /**
     * Takes the next buffer from the Queue.
     */
    @Nullable
    private ByteBuffer takeNext() throws InterruptedException, IOException {
        ByteBuffer buffer = null;
        while (buffer == null) {
            if (interruption != null) {
                final InterruptedIOException ioe = new InterruptedIOException();
                ioe.initCause(interruption);
                throw ioe;
            }
            if (eos.get()) {
                return buffers.poll();
            } else if (timeout != null) {
                buffer = buffers.poll(timeout.toMillis(), TimeUnit.MILLISECONDS); // block during the timeout
                if (buffer == null) {
                    throw new InterruptedByTimeoutException();
                }
            } else {
                buffer = buffers.take(); // block indefinitely
            }
        }
        return buffer;
    }

    /**
     * Drains the Queue.
     */
    private void drain() {
        ByteBuffer buffer;
        do {
            buffer = buffers.poll();
        } while (buffer != null);
    }

    /**
     * Checks whether the {@link InputStream} has EOS. Note that EOS may not have reached yet.
     */
    public boolean isEos() {
        return eos.get();
    }

    /**
     * Marks {@link InputStream} with the EOS, meaning that there will be no more
     * {@link ByteBuffer} data chunks available to this {@link InputStream}.
     */
    public void setEos() {
        eos.set(true);
        buffers.add(EMPTY_BUFFER); // to wake the BlockingQueue
    }

    /**
     * Adds new input {@link ByteBuffer} data chunk to the {@link InputStream}.
     */
    public void add(ByteBuffer buffer) {
        requireNonNull(buffer, "buffer");
        if (eos.get()) {
            throw new IllegalStateException("Already closed");
        }
        if (!buffers.add(buffer)) {
            throw new IllegalStateException("Unable to add new buffer");
        }
    }

    public void interrupt(Throwable interruption) {
        this.interruption = requireNonNull(interruption, "interruption");
        buffers.add(EMPTY_BUFFER); // to wake the BlockingQueue
    }

    /**
     * Closes this {@link InputStream} and releases any system resources associated with the stream.
     * Sets EOS, if it has not been set yet. Drains all the allocated buffers.
     */
    @Override
    public void close() {
        // set EOS
        setEos();
        // drain the queue
        drain();
    }

    @Override
    public int available() {
        final ByteBuffer buffer = peekBuffer();
        if (buffer == null) {
            return 0;
        }
        return buffer.remaining();
    }

    @Override
    public int read() throws IOException {
        //noinspection Convert2MethodRef
        return read(buffer -> readFromBuffer(buffer));
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        return read(buffer -> readFromBuffer(buffer, bytes, off, len));
    }

    private int read(Function<ByteBuffer, Integer> reader) throws IOException {
        try {
            ByteBuffer buffer = buffer();
            if (buffer == null) {
                return -1;
            }
            int b;
            while (buffer != null) {
                b = reader.apply(buffer);
                if (b == -1) {
                    // no more data available in the current buffer
                    buffer = next();
                } else {
                    return b;
                }
            }
            return -1; // there is no more data and there are no more byte buffers
        } catch (InterruptedException e) {
            final InterruptedIOException ioe = new InterruptedIOException();
            ioe.initCause(e);
            throw ioe;
        }
    }

    private static int readFromBuffer(ByteBuffer buffer) {
        return buffer.hasRemaining() ? (buffer.get() & 0xFF) : -1;
    }

    private static int readFromBuffer(ByteBuffer buffer, byte[] bytes, int off, int len) {
        if (!buffer.hasRemaining()) {
            return -1;
        }
        len = Math.min(len, buffer.remaining());
        buffer.get(bytes, off, len);
        return len;
    }
}
