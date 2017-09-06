/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.encoding;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;

/**
 * A {@link StreamDecoder} that user zlib ('gzip' or 'deflate'). Netty implementation used to allow
 * for incremental decoding using an {@link EmbeddedChannel}.
 */
class ZlibStreamDecoder implements StreamDecoder {

    private final EmbeddedChannel decoder;

    ZlibStreamDecoder(ZlibWrapper zlibWrapper) {
        decoder = new EmbeddedChannel(false, ZlibCodecFactory.newZlibDecoder(zlibWrapper));
    }

    @Override
    public HttpData decode(HttpData obj) {
        ByteBuf compressed = Unpooled.wrappedBuffer(obj.array(), obj.offset(), obj.length());
        decoder.writeInbound(compressed);
        return HttpData.of(fetchDecoderOutput());
    }

    @Override
    public HttpData finish() {
        if (decoder.finish()) {
            return HttpData.of(fetchDecoderOutput());
        } else {
            return HttpData.EMPTY_DATA;
        }
    }

    // Mostly copied from netty's HttpContentDecoder.
    private byte[] fetchDecoderOutput() {
        CompositeByteBuf decoded = Unpooled.compositeBuffer();
        for (;;) {
            ByteBuf buf = decoder.readInbound();
            if (buf == null) {
                break;
            }
            if (!buf.isReadable()) {
                buf.release();
                continue;
            }
            decoded.addComponent(true, buf);
        }
        byte[] ret = ByteBufUtil.getBytes(decoded);
        decoded.release();
        return ret;
    }
}
