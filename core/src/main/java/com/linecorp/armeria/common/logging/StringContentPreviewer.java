/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import java.nio.charset.Charset;

import com.google.common.annotations.VisibleForTesting;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

final class StringContentPreviewer extends AbstractContentPreviewer {

    private final Charset charset;
    private final int maxLength;

    StringContentPreviewer(int maxLength, Charset charset) {
        this(maxLength, charset, inflateMaxLength(maxLength, charset));
    }

    private StringContentPreviewer(int maxLength, Charset charset, int inflatedMaxLength) {
        super(inflatedMaxLength);
        this.maxLength = maxLength;
        this.charset = charset;
    }

    private static int inflateMaxLength(int maxLength, Charset charset) {
        final long maxBytesPerChar = (long) Math.ceil(CharsetUtil.encoder(charset).maxBytesPerChar());
        return (int) Long.min(Integer.MAX_VALUE, maxBytesPerChar * maxLength);
    }

    @VisibleForTesting
    int maxLength() {
        return maxLength;
    }

    @Override
    String produce(ByteBuf wrappedBuffer) {
        final String produced = wrappedBuffer.toString(wrappedBuffer.readerIndex(),
                                                       wrappedBuffer.readableBytes(), charset);
        if (produced.length() > maxLength) {
            return produced.substring(0, maxLength);
        } else {
            return produced;
        }
    }
}
