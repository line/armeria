/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common.encoding;

import java.io.IOException;
import java.io.OutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.compression.SnappyFrameEncoder;

final class SnappyFramedOutputStream extends OutputStream {

    private final SimpleSnappyFrameEncoder encoder = new SimpleSnappyFrameEncoder();
    private final ByteBuf out;

    SnappyFramedOutputStream(ByteBuf out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        // It is not used internally.
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        final ByteBuf input = Unpooled.wrappedBuffer(b, off, len);
        try {
            encoder.encode(input, out);
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to compress input with Snappy framing format:", e);
        }
    }

    /**
     * A {@link SnappyFrameEncoder} that does not require a {@link ChannelHandlerContext}.
     * This is a workaround for accessing the protected
     * {@link SnappyFrameEncoder#encode(ChannelHandlerContext, ByteBuf, ByteBuf)}.
     */
    private static class SimpleSnappyFrameEncoder extends SnappyFrameEncoder {

        public void encode(ByteBuf in, ByteBuf out) throws Exception {
            // It is safe to set ctx as null because SnappyFrameEncoder.encoder() does not use it.
            encode(null, in, out);
        }
    }
}
