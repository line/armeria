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

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.BrotliDecoder;

/**
 * A {@link StreamDecoder} that decompresses data encoded with the brotli format ('br').
 */
final class BrotliStreamDecoder extends AbstractStreamDecoder {

    BrotliStreamDecoder(BrotliDecoder brotliDecoder, ByteBufAllocator alloc, int maxLength) {
        // BrotliDecoder does not limit the max output size. If the output buffer exceeds 4MiB, it is
        // chunked into pieces of 4MiB. As a workaround, the max length is checked at the `StreamDecoder`
        // level after decoding.
        super(brotliDecoder, alloc, maxLength);
    }
}
