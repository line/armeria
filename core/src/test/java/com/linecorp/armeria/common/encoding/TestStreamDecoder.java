/*
 * Copyright 2024 LINE Corporation
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

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;

class TestStreamDecoder extends AbstractStreamDecoder {

    private final EmbeddedChannel childDecoder;
    private final int childMaxLength;

    TestStreamDecoder(EmbeddedChannel embeddedChannel, ChannelHandler handler,
                                ByteBufAllocator alloc, int maxLength) {
        super(handler, alloc, maxLength);
        childDecoder = embeddedChannel;
        childDecoder.config().setAllocator(alloc);
        childMaxLength = maxLength;
    }

    @Override
    public HttpData decode(HttpData obj) {
        try {
            childDecoder.writeInbound(obj.byteBuf());
        } catch (DecompressionException ex) {
            final String message = ex.getMessage();
            if (message != null && message.startsWith("Decompression buffer has reached maximum size:")) {
                throw ContentTooLargeException.builder()
                                              .maxContentLength(childMaxLength)
                                              .cause(ex)
                                              .build();
            }
            throw ex;
        }
        return fetchDecoderOutput();
    }
}
