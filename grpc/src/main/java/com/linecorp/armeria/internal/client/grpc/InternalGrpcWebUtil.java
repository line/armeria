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

package com.linecorp.armeria.internal.client.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.util.ByteProcessor;
import io.netty.util.ByteProcessor.IndexOfProcessor;

public final class InternalGrpcWebUtil {

    private static final ByteProcessor FIND_COLON = new IndexOfProcessor((byte) ':');

    public static ByteBuf messageBuf(DeframedMessage message, ByteBufAllocator alloc) throws IOException {
        final ByteBuf messageBuf = message.buf();
        final ByteBuf buf;
        if (messageBuf != null) {
            buf = messageBuf;
        } else {
            // TODO(minwoox) Optimize this by creating buffer with the sensible initial capacity.
            buf = alloc.compositeBuffer();
            boolean success = false;
            try (ByteBufOutputStream os = new ByteBufOutputStream(buf);
                 InputStream stream = message.stream()) {
                assert stream != null;
                ByteStreams.copy(stream, os);
                success = true;
            } catch (Throwable t) {
                if (!success) {
                    buf.release();
                }
                throw t;
            }
        }
        return buf;
    }

    @Nullable
    public static HttpHeaders parseGrpcWebTrailers(ByteBuf buf) {
        final HttpHeadersBuilder trailers = HttpHeaders.builder();
        while (buf.readableBytes() > 0) {
            int start = buf.forEachByte(ByteProcessor.FIND_NON_LINEAR_WHITESPACE);
            if (start == -1) {
                return null;
            }
            int endExclusive;
            if (buf.getByte(start) == ':') {
                // We need to skip the pseudoheader colon when searching for the separator.
                buf.skipBytes(1);
                endExclusive = buf.forEachByte(FIND_COLON);
                buf.readerIndex(start);
            } else {
                endExclusive = buf.forEachByte(FIND_COLON);
            }
            if (endExclusive == -1) {
                return null;
            }
            final CharSequence name = buf.readCharSequence(endExclusive - start, StandardCharsets.UTF_8);
            buf.readerIndex(endExclusive + 1);
            start = buf.forEachByte(ByteProcessor.FIND_NON_LINEAR_WHITESPACE);
            buf.readerIndex(start);
            endExclusive = buf.forEachByte(ByteProcessor.FIND_CRLF);
            final CharSequence value = buf.readCharSequence(endExclusive - start, StandardCharsets.UTF_8);
            trailers.add(name, value.toString());
            start = buf.forEachByte(ByteProcessor.FIND_NON_CRLF);
            if (start != -1) {
                buf.readerIndex(start);
            } else {
                // Nothing but CRLF remaining, we're done.
                buf.skipBytes(buf.readableBytes());
            }
        }
        return trailers.build();
    }

    private InternalGrpcWebUtil() {}
}
