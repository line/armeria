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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.SPACE;
import static io.netty.util.internal.StringUtil.decodeHexByte;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import io.netty.util.CharsetUtil;

final class QueryStringDecoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/7d6d953153697bd66c3b01ca8ec73c4494a81788/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringDecoder.java

    @SuppressWarnings("checkstyle:FallThrough")
    static QueryParams decodeParams(@Nullable String s, int paramsLimit, boolean semicolonAsSeparator) {
        if (s == null) {
            return QueryParams.of();
        }

        final int len = s.length();
        if (len == 0) {
            return QueryParams.of();
        }

        final QueryParamsBuilder params = QueryParams.builder();
        int nameStart = 0;
        int valueStart = -1;
        int i;
        loop:
        for (i = 0; i < len; i++) {
            switch (s.charAt(i)) {
                case '=':
                    if (nameStart == i) {
                        nameStart = i + 1;
                    } else if (valueStart < nameStart) {
                        valueStart = i + 1;
                    }
                    break;
                case ';':
                    if (!semicolonAsSeparator) {
                        continue;
                    }
                    // fall-through
                case '&':
                    if (addParam(s, nameStart, valueStart, i, params)) {
                        paramsLimit--;
                        if (paramsLimit == 0) {
                            return params.build();
                        }
                    }
                    nameStart = i + 1;
                    break;
                case '#':
                    break loop;
                default:
                    // continue
            }
        }
        addParam(s, nameStart, valueStart, i, params);
        return params.build();
    }

    private static boolean addParam(String s, int nameStart, int valueStart, int valueEnd,
                                    QueryParamsBuilder params) {
        if (nameStart >= valueEnd) {
            return false;
        }
        if (valueStart <= nameStart) {
            valueStart = valueEnd + 1;
        }
        final String name = decodeComponent(s, nameStart, valueStart - 1);
        final String value = decodeComponent(s, valueStart, valueEnd);
        params.add(name, value);
        return true;
    }

    @VisibleForTesting
    static String decodeComponent(String s, int from, int toExcluded) {
        final int len = toExcluded - from;
        if (len <= 0) {
            return EMPTY_STRING;
        }
        int firstEscaped = -1;
        for (int i = from; i < toExcluded; i++) {
            final char c = s.charAt(i);
            if (c == '%' || c == '+') {
                firstEscaped = i;
                break;
            }
        }
        if (firstEscaped == -1) {
            return s.substring(from, toExcluded);
        }

        final CharsetDecoder decoder = CharsetUtil.decoder(StandardCharsets.UTF_8);

        // Each encoded byte takes 3 characters (e.g. "%20")
        final int decodedCapacity = (toExcluded - firstEscaped) / 3;
        final ByteBuffer byteBuf = ByteBuffer.allocate(decodedCapacity);
        final CharBuffer charBuf = CharBuffer.allocate(decodedCapacity);

        final StringBuilder strBuf = new StringBuilder(len);
        strBuf.append(s, from, firstEscaped);

        for (int i = firstEscaped; i < toExcluded; i++) {
            final char c = s.charAt(i);
            if (c != '%') {
                strBuf.append(c != '+' ? c : SPACE);
                continue;
            }

            byteBuf.clear();
            do {
                if (i + 3 > toExcluded) {
                    throw new IllegalArgumentException(
                            "unterminated escape sequence at index " + i + " of: " + s);
                }
                byteBuf.put(decodeHexByte(s, i + 1));
                i += 3;
            } while (i < toExcluded && s.charAt(i) == '%');
            i--;

            byteBuf.flip();
            charBuf.clear();
            CoderResult result = decoder.reset().decode(byteBuf, charBuf, true);
            try {
                if (!result.isUnderflow()) {
                    result.throwException();
                }
                result = decoder.flush(charBuf);
                if (!result.isUnderflow()) {
                    result.throwException();
                }
            } catch (CharacterCodingException ex) {
                throw new IllegalStateException(ex);
            }
            strBuf.append(charBuf.flip());
        }
        return strBuf.toString();
    }

    private QueryStringDecoder() {}
}
