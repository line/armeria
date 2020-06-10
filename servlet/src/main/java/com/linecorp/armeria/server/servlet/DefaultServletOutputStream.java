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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Arrays;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import com.linecorp.armeria.common.HttpData;

/**
 * Servlets output streams (wrapper classes) that control access to the flow.
 */
final class DefaultServletOutputStream extends ServletOutputStream {
    private final DefaultServletHttpResponse response;

    DefaultServletOutputStream(DefaultServletHttpResponse response) {
        requireNonNull(response, "response");
        this.response = response;
    }

    @Override
    public boolean isReady() {
        return response.getResponseWriter().isOpen();
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void close() throws IOException {
        response.getResponseWriter().close();
    }

    @Override
    public void flush() throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void write(int b) throws IOException {
        final byte[] bytes = new byte[1];
        bytes[0] = (byte) b;
        write(bytes);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        requireNonNull(b, "b");
        checkArgument(off >= 0, "off: %s (expected: >= 0)", off);
        checkArgument(len >= 0, "len: %s (expected: >= 0)", len);
        response.write(HttpData.copyOf(Arrays.copyOfRange(b, off, len)));
    }

    @Override
    public void write(byte[] b) throws IOException {
        requireNonNull(b, "b");
        write(b, 0, b.length);
    }
}
