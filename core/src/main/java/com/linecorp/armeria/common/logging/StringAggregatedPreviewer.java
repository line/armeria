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

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

import io.netty.buffer.ByteBufHolder;

final class StringAggregatedPreviewer implements ContentPreviewer {

    private final CharBuffer buffer;
    private final Charset defaultCharset;
    @Nullable
    private CharsetDecoder decoder;

    @Nullable
    private String produced;

    private ByteBuffer remainedBuffer;

    StringAggregatedPreviewer(int length, Charset defaultCharset) {
        buffer = CharBuffer.allocate(length);
        this.defaultCharset = defaultCharset;
    }

    @Override
    public void onData(HttpData data) {
        checkState(decoder != null, "decoder should not be null.");
        if (produced != null || !buffer.hasRemaining()) {
            return;
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

    @Override
    public void onHeaders(HttpHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return;
        }
        final Charset charset = contentType.charset().orElse(defaultCharset);
        decoder = charset.newDecoder();
        remainedBuffer = ByteBuffer.allocate((int)Math.ceil(charset.newEncoder().maxBytesPerChar()));
        LoggerFactory.getLogger(getClass()).debug("MAX BYTES {}", remainedBuffer.capacity());
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
                while (remainedBuffer.hasRemaining() && buf.hasRemaining()) {
                    remainedBuffer.put(buf.get());
                }
            }
        }
    }
}
