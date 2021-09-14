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

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.InterruptedByTimeoutException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;

/**
 * An {@link InputStream} implementation that exposes dynamically available content
 * of series of the {@link ByteBuf} data chunks until the EOS flag set for this stream.
 * All {@link InputStream} reading operations will block until next data chunk gets available or
 * EOS flag set for the stream.
 * When the EOS flag set, all the available data will be read fully, but no more
 * new {@link ByteBuf} data chunks will be permitted.
 */
@UnstableApi
public final class ByteBuffersBackedInputStream extends InputStream {

    private final BlockingQueue<ByteBuf> buffers = new LinkedBlockingQueue<>();
    private final AtomicBoolean eos = new AtomicBoolean(false);
    @Nullable
    private volatile ByteBuf nextBuffer;
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
    private ByteBuf peekBuffer() {
        final ByteBuf buffer = nextBuffer;
        return (buffer == null) ? buffers.peek() : buffer;
    }

    @Nullable
    private ByteBuf buffer() throws InterruptedException, IOException {
        ByteBuf buffer = nextBuffer;
        if (buffer == null) {
            buffer = takeNext();
        }
        return nextBuffer = buffer;
    }

    @Nullable
    private ByteBuf next() throws InterruptedException, IOException {
        final ByteBuf buffer = takeNext();
        return nextBuffer = buffer;
    }

    /**
     * Takes the next buffer from the Queue.
     */
    @Nullable
    private ByteBuf takeNext() throws InterruptedException, IOException {
        ByteBuf buffer = null;
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
        ByteBuf buffer;
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
     * {@link ByteBuf} data chunks available to this {@link InputStream}.
     */
    public void setEos() {
        eos.set(true);
        buffers.add(EMPTY_BUFFER); // to wake the BlockingQueue
    }

    /**
     * Adds new input {@link ByteBuf} data chunk to the {@link InputStream}.
     */
    public void add(ByteBuf buffer) {
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
        final ByteBuf buffer = peekBuffer();
        if (buffer == null) {
            return 0;
        }
        return buffer.readableBytes();
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

    private int read(Function<ByteBuf, Integer> reader) throws IOException {
        try {
            ByteBuf buffer = buffer();
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

    private static int readFromBuffer(ByteBuf buffer) {
        return buffer.isReadable() ? (buffer.readByte() & 0xFF) : -1;
    }

    private static int readFromBuffer(ByteBuf buffer, byte[] bytes, int off, int len) {
        if (!buffer.isReadable()) {
            return -1;
        }
        len = Math.min(len, buffer.readableBytes());
        buffer.readBytes(bytes, off, len);
        return len;
    }
}
