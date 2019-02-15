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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;

import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;

final class StringAggregatingPreviewer extends ByteBufAggreatingPreviewer {
    private final Charset defaultCharset;
    private Charset charset;
    private final int length;

    StringAggregatingPreviewer(int length, Charset defaultCharset) {
        checkArgument(length >= 0, "length: %d (expected: >= 0)", length);
        this.defaultCharset = requireNonNull(defaultCharset, "defaultCharset");
        this.length = length;
    }

    @Override
    public void onHeaders(HttpHeaders headers) {
        super.onHeaders(headers);
        if (headers.contentType() != null) {
            charset = headers.contentType().charset().orElse(defaultCharset);
        } else {
            charset = defaultCharset;
        }
        final long maxBytesPerChar = (long) Math.ceil(charset.newEncoder().maxBytesPerChar());
        final long capacity = maxBytesPerChar * (long)length;
        if (capacity > Integer.MAX_VALUE) {
            maxAggregatedLength(Integer.MAX_VALUE);
        } else {
            maxAggregatedLength((int)capacity);
        }
    }

    @Override
    protected String reproduce(HttpHeaders headers, ByteBuf wrappedBuffer) {
        final String produced = wrappedBuffer.toString(charset);
        if (produced.length() > length) {
            return produced.substring(0, length);
        } else {
            return produced;
        }
    }
}
