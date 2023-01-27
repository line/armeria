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

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.JZlibDecoder;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibDecoder;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.util.internal.SystemPropertyUtil;

/**
 * A {@link StreamDecoder} that uses zlib ('gzip' or 'deflate').
 */
final class ZlibStreamDecoder extends AbstractStreamDecoder {

    private static final boolean noJdkZlibDecoder =
            SystemPropertyUtil.getBoolean("io.netty.noJdkZlibDecoder", false);

    ZlibStreamDecoder(ZlibWrapper zlibWrapper, ByteBufAllocator alloc, int maxLength) {
        super(newZlibDecoder(zlibWrapper, maxLength), alloc, maxLength);
    }

    private static ZlibDecoder newZlibDecoder(ZlibWrapper wrapper, int maxLength) {
        if (noJdkZlibDecoder) {
            return new JZlibDecoder(wrapper, maxLength);
        } else {
            return new JdkZlibDecoder(wrapper, true, maxLength);
        }
    }
}
