/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class StreamWriterOutputStream<T> extends OutputStream {

    private final StreamWriter<T> writer;
    private final Function<? super HttpData, ? extends T> httpDataConverter;
    private final ByteBuf buffer = Unpooled.buffer();
    private final int maxBufferSize;
    private boolean closed;

    StreamWriterOutputStream(StreamWriter<T> writer,
                             Function<? super HttpData, ? extends T> httpDataConverter,
                             int maxBufferSize) {
        requireNonNull(writer, "writer");
        requireNonNull(httpDataConverter, "httpDataConverter");
        checkArgument(maxBufferSize > 0, "maxBufferSize should be positive");
        this.writer = writer;
        this.httpDataConverter = httpDataConverter;
        this.maxBufferSize = maxBufferSize;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        maybeDrain();
        buffer.writeByte(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        maybeDrain();
        buffer.writeBytes(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        drain();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        flush();
        writer.close();
        closed = true;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (!writer.isOpen()) {
            closed = true;
            throw new IOException("Stream closed");
        }
    }

    private void maybeDrain() {
        if (buffer.readableBytes() >= maxBufferSize) {
            drain();
        }
    }

    private void drain() {
        if (!buffer.isReadable() || !writer.isOpen()) {
            return;
        }
        final byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        writer.write(httpDataConverter.apply(HttpData.copyOf(data)));
    }
}
