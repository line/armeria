/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.servlet;

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import io.netty.buffer.ByteBuf;

/**
 * The servlet input stream.
 */
final class DefaultServletInputStream extends ServletInputStream {
    private final ByteBuf source;

    /**
     * Creates a new instance.
     */
    DefaultServletInputStream(ByteBuf source) {
        requireNonNull(source, "source");
        this.source = source;
    }

    @Override
    public int readLine(byte[] b, int off, int len) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isFinished() {
        return source.readableBytes() == 0;
    }

    @Override
    public boolean isReady() {
        return source.readableBytes() != 0;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        requireNonNull(readListener, "readListener");
    }

    @Override
    public long skip(long n) throws IOException {
        final long skipLen = Math.min(source.readableBytes(), n);
        source.skipBytes((int) skipLen);
        return skipLen;
    }

    @Override
    public int available() throws IOException {
        return source.readableBytes();
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int read() throws IOException {
        if (isFinished()) {
            return -1;
        }
        return source.readByte();
    }
}
