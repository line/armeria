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

import static com.google.common.base.Preconditions.checkState;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBufHolder;

final class StringAggregatedWriter implements ContentPreviewWriter {

    private final CharBuffer buffer;
    private final Charset defaultCharset;
    @Nullable
    private CharsetDecoder decoder;

    @Nullable
    private String produced;

    private final ByteBuffer remainedBuffer;

    StringAggregatedWriter(int length, Charset defaultCharset) {
        buffer = CharBuffer.allocate(length);
        this.defaultCharset = defaultCharset;
        remainedBuffer = ByteBuffer.allocate(10);
    }

    @Override
    public void write(HttpHeaders headers, HttpData data) {
        if (produced != null || !buffer.hasRemaining()) {
            return;
        }
        if (decoder == null) {
            if (headers.contentType() == null) {
                decoder = defaultCharset.newDecoder();
            } else {
                decoder = headers.contentType().charset().orElse(defaultCharset).newDecoder();
            }
        }
        if (data instanceof ByteBufHolder) {
            Arrays.stream(((ByteBufHolder) data).content().nioBuffers()).forEach(this::append);
        } else {
            append(ByteBuffer.wrap(data.array(), data.offset(), data.length() - data.offset()));
        }
    }

    @Override
    public String produce() {
        if (produced == null) {
            produced = new String(buffer.array(), 0, buffer.position());
        }
        return produced;
    }

    private void append(ByteBuffer buf) {
        checkState(decoder != null, "decoder should not be null to append the content preview.");
        if (produced != null || !buffer.hasRemaining() || !buf.hasRemaining()) {
            return;
        }

        if (remainedBuffer.position() > 0) {
            while (remainedBuffer.hasRemaining() && buf.hasRemaining()) {
                remainedBuffer.put(buf.get());
            }
            final int prevPos = remainedBuffer.position();
            remainedBuffer.flip();
            if (decoder.decode(remainedBuffer, buffer, false).isUnderflow() && buffer.hasRemaining()) {
                if (remainedBuffer.position() == 0) {
                    remainedBuffer.position(prevPos);
                    remainedBuffer.limit(remainedBuffer.capacity());
                    return;
                }
                buf.position(buf.position() - (prevPos - remainedBuffer.position()));
                remainedBuffer.clear();
            }
        }

        if (buffer.hasRemaining()) {
            if (decoder.decode(buf, buffer, false).isUnderflow() && buffer.hasRemaining()) {
                remainedBuffer.put(buf);
            }
        }
    }
}
