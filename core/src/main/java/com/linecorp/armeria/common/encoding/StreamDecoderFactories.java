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

package com.linecorp.armeria.common.encoding;

import java.util.List;

import com.google.common.collect.ImmutableList;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.BrotliDecoder;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
import io.netty.handler.codec.compression.ZlibWrapper;

enum StreamDecoderFactories implements StreamDecoderFactory {
    BROTLI {
        @Override
        public String encodingHeaderValue() {
            return "br";
        }

        @Override
        public StreamDecoder newDecoder(ByteBufAllocator alloc, int maxLength) {
            return new BrotliStreamDecoder(new BrotliDecoder(), alloc, maxLength);
        }
    },
    GZIP {
        @Override
        public String encodingHeaderValue() {
            return "gzip";
        }

        @Override
        public StreamDecoder newDecoder(ByteBufAllocator alloc, int maxLength) {
            return new ZlibStreamDecoder(ZlibWrapper.GZIP, alloc, maxLength);
        }
    },
    DEFLATE {
        @Override
        public String encodingHeaderValue() {
            return "deflate";
        }

        @Override
        public StreamDecoder newDecoder(ByteBufAllocator alloc, int maxLength) {
            return new ZlibStreamDecoder(ZlibWrapper.ZLIB, alloc, maxLength);
        }
    },
    SNAPPY {
        @Override
        public String encodingHeaderValue() {
            return "x-snappy-framed";
        }

        @Override
        public StreamDecoder newDecoder(ByteBufAllocator alloc, int maxLength) {
            return new SnappyStreamDecoder(new SnappyFrameDecoder(), alloc, maxLength);
        }
    };

    static final List<StreamDecoderFactory> ALL;

    static {
        if (Brotli.isAvailable()) {
            ALL = ImmutableList.copyOf(values());
        } else {
            ALL = ImmutableList.of(GZIP, DEFLATE, SNAPPY);
        }
    }
}
