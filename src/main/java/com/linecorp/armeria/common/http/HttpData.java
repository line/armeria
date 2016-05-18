/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

public interface HttpData extends HttpObject {

    HttpData EMPTY_DATA = DefaultHttpData.EMPTY_DATA;

    static HttpData of(byte[] data) {
        requireNonNull(data, "data");
        if (data.length == 0) {
            return EMPTY_DATA;
        }

        return new DefaultHttpData(data, 0, data.length);
    }

    static HttpData of(byte[] data, int offset, int length) {
        requireNonNull(data);
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                    "offset: " + offset + ", length: " + length + ", data.length: " + data.length);
        }
        if (data.length == 0) {
            return EMPTY_DATA;
        }

        return new DefaultHttpData(data, offset, length);
    }

    static HttpData of(Charset charset, String text) {
        requireNonNull(charset, "charset");
        requireNonNull(text, "text");
        if (text.isEmpty()) {
            return EMPTY_DATA;
        }

        return of(text.getBytes(charset));
    }

    static HttpData of(ByteBuf buf) {
        return of(ByteBufUtil.getBytes(buf));
    }

    static HttpData of(Charset charset, String format, Object... args) {
        requireNonNull(charset, "charset");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return of(String.format(Locale.ENGLISH, format, args).getBytes(charset));
    }

    static HttpData ofUtf8(String text) {
        return of(StandardCharsets.UTF_8, text);
    }

    static HttpData ofUtf8(String format, Object... args) {
        return of(StandardCharsets.UTF_8, format, args);
    }

    static HttpData ofAscii(String text) {
        return of(StandardCharsets.US_ASCII, text);
    }

    static HttpData ofAscii(String format, Object... args) {
        return of(StandardCharsets.US_ASCII, format, args);
    }

    byte[] array();

    int offset();

    int length();

    default boolean isEmpty() {
        return length() == 0;
    }

    default String toString(Charset charset) {
        requireNonNull(charset, "charset");
        return new String(array(), offset(), length(), charset);
    }

    default String toStringUtf8() {
        return toString(StandardCharsets.UTF_8);
    }

    default String toStringAscii() {
        return toString(StandardCharsets.US_ASCII);
    }
}
