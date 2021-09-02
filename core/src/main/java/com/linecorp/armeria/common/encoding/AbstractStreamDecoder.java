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

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Skeletal {@link StreamDecoder} implementation. Netty implementation used to allow
 * for incremental decoding using an {@link EmbeddedChannel}.
 */
class AbstractStreamDecoder implements StreamDecoder {

    private final EmbeddedChannel decoder;

    protected AbstractStreamDecoder(ChannelHandler handler, ByteBufAllocator alloc) {
        decoder = new EmbeddedChannel(false, handler);
        decoder.config().setAllocator(alloc);
    }

    @Override
    public HttpData decode(HttpData obj) {
        decoder.writeInbound(obj.byteBuf());
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

    // Mostly copied from netty's HttpContentDecoder.
    protected HttpData fetchDecoderOutput() {
        @Nullable ByteBuf decoded = null;
        for (;;) {
            final ByteBuf buf = decoder.readInbound();
            if (buf == null) {
                break;
            }
            if (!buf.isReadable()) {
                buf.release();
                continue;
            }
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
}
