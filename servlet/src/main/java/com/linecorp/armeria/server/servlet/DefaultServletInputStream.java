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

import javax.annotation.Nullable;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import io.netty.buffer.ByteBuf;

/**
 * The servlet input stream.
 */
public class DefaultServletInputStream extends ServletInputStream {
    @Nullable
    private ByteBuf source;

    /**
     * Set content.
     */
    public void setContent(ByteBuf source) {
        this.source = source;
    }

    @Override
    public int readLine(byte[] b, int off, int len) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * There is no new HttpContent input for this request, and all of the current content has been read.
     * @return True = false after reading.
     */
    @Override
    public boolean isFinished() {
        return source == null || source.readableBytes() == 0;
    }

    /**
     * HttpContent has been read in at least once and not all of it has been read,
     * or the HttpContent queue is not empty.
     */
    @Override
    public boolean isReady() {
        return source != null && source.readableBytes() != 0;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        requireNonNull(readListener, "readListener");
    }

    /**
     * Skip n bytes.
     */
    @Override
    public long skip(long n) throws IOException {
        if (source == null) {
            return 0;
        }
        final long skipLen = Math.min(source.readableBytes(), n);
        source.skipBytes((int) skipLen);
        return skipLen;
    }

    /**
     * Get number of readable bytes.
     * @return Number of readable bytes.
     */
    @Override
    public int available() throws IOException {
        return null == source ? 0 : source.readableBytes();
    }

    @Override
    public void close() throws IOException {
        source = null;
    }

    /**
     * Try to update current, then read len bytes and copy to b (start with off subscript).
     * @return The number of bytes actually read.
     */
    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Try updating current, then read a byte, and return, where int is returned,
     * but third-party frameworks treat it as one byte instead of four.
     */
    @Override
    public int read() throws IOException {
        if (isFinished() || source == null) {
            return -1;
        }
        return source.readByte();
    }
}
