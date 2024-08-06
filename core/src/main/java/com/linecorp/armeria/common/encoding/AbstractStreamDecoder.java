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

package com.linecorp.armeria.common.encoding;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;

/**
 * Skeletal {@link StreamDecoder} implementation. Netty implementation used to allow
 * for incremental decoding using an {@link EmbeddedChannel}.
 */
class AbstractStreamDecoder implements StreamDecoder {

    private final EmbeddedChannel decoder;
    private final int maxLength;
    private int decodedLength;

    protected AbstractStreamDecoder(ChannelHandler handler, ByteBufAllocator alloc, int maxLength) {
        decoder = new EmbeddedChannel(false, handler);
        decoder.config().setAllocator(alloc);
        this.maxLength = maxLength;
    }

    @Override
    public HttpData decode(HttpData obj) {
        try {
            decoder.writeInbound(obj.byteBuf());
        } catch (DecompressionException ex) {
            final String message = ex.getMessage();
            if (message != null && message.startsWith("Decompression buffer has reached maximum size:")) {
                throw ContentTooLargeException.builder()
                                              .maxContentLength(maxLength)
                                              .cause(ex)
                                              .build();
            } else {
                throw ex;
            }
        }
        return fetchDecoderOutput();
    }

    @Override
    public HttpData finish() {
        if (decoder.finish()) {
            return fetchDecoderOutput();
        } else {
            return HttpData.empty();
        }
    }

    @Override
    public int maxLength() {
        return maxLength;
    }

    // Mostly copied from netty's HttpContentDecoder.
    protected HttpData fetchDecoderOutput() {
        ByteBuf decoded = null;
        for (;;) {
            final ByteBuf buf = decoder.readInbound();
            if (buf == null) {
                break;
            }
            if (!buf.isReadable()) {
                buf.release();
                continue;
            }
            maybeCheckOverflow(decoded, buf);
            if (decoded == null) {
                decoded = buf;
            } else {
                try {
                    decoded.writeBytes(buf);
                } finally {
                    buf.release();
                }
            }
        }

        if (decoded == null) {
            return HttpData.empty();
        }

        return HttpData.wrap(decoded);
    }

    private void maybeCheckOverflow(@Nullable ByteBuf decoded, ByteBuf newBuf) {
        if (maxLength <= 0 || maxLength == Integer.MAX_VALUE) {
            return;
        }

        decodedLength += newBuf.readableBytes();
        if (decodedLength > maxLength) {
            if (decoded != null) {
                decoded.release();
            }
            newBuf.release();
            throw ContentTooLargeException.builder()
                                          .maxContentLength(maxLength)
                                          .transferred(decodedLength)
                                          .build();
        }
    }
}
