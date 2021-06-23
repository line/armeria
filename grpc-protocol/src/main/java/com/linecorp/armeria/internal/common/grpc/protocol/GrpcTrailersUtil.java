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

package com.linecorp.armeria.internal.common.grpc.protocol;

import static io.netty.util.AsciiString.c2b;

import java.util.Map;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.StatusMessageEscaper;
import com.linecorp.armeria.internal.common.util.StringUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.AsciiString;

/**
 * Utility for creating response trailers for a gRPC status. Trailers are only returned from a server.
 */
public final class GrpcTrailersUtil {

    /**
     * Adds the given gRPC status code, and optionally an error message, to the specified
     * {@link HttpHeadersBuilder}. This will also set the {@code endOfStream} of the {@link HttpHeadersBuilder}
     * as {@code true}.
     */
    public static void addStatusMessageToTrailers(
            HttpHeadersBuilder trailersBuilder, int code, @Nullable String message) {
        trailersBuilder.endOfStream(true);
        trailersBuilder.add(GrpcHeaderNames.GRPC_STATUS, StringUtil.toString(code));
        if (message != null) {
            trailersBuilder.add(GrpcHeaderNames.GRPC_MESSAGE, StatusMessageEscaper.escape(message));
        }
    }

    /**
     * Serializes the specified {@link HttpHeaders} to send as a body in gRPC-Web.
     */
    public static ByteBuf serializeTrailersAsMessage(ByteBufAllocator alloc, HttpHeaders trailers) {
        final ByteBuf serialized = alloc.buffer();
        boolean success = false;
        try {
            for (Map.Entry<AsciiString, String> trailer : trailers) {
                encodeHeader(trailer.getKey(), trailer.getValue(), serialized);
            }
            success = true;
        } finally {
            if (!success) {
                serialized.release();
            }
        }
        return serialized;
    }

    // Copied from io.netty.handler.codec.http.HttpHeadersEncoder
    private static void encodeHeader(CharSequence name, CharSequence value, ByteBuf buf) {
        final int nameLen = name.length();
        final int valueLen = value.length();
        final int entryLen = nameLen + valueLen + 4;
        buf.ensureWritable(entryLen);
        int offset = buf.writerIndex();
        writeAscii(buf, offset, name, nameLen);
        offset += nameLen;
        buf.setByte(offset++, ':');
        buf.setByte(offset++, ' ');
        writeAscii(buf, offset, value, valueLen);
        offset += valueLen;
        buf.setByte(offset++, '\r');
        buf.setByte(offset++, '\n');
        buf.writerIndex(offset);
    }

    private static void writeAscii(ByteBuf buf, int offset, CharSequence value, int valueLen) {
        if (value instanceof AsciiString) {
            ByteBufUtil.copy((AsciiString) value, 0, buf, offset, valueLen);
        } else {
            writeCharSequence(buf, offset, value, valueLen);
        }
    }

    private static void writeCharSequence(ByteBuf buf, int offset, CharSequence value, int valueLen) {
        for (int i = 0; i < valueLen; ++i) {
            buf.setByte(offset++, c2b(value.charAt(i)));
        }
    }

    private GrpcTrailersUtil() {}
}
