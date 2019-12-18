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
import static io.netty.util.internal.StringUtil.decodeHexNibble;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.internal.TemporaryThreadLocals;

final class QueryStringDecoder {

    private static final char UNKNOWN_CHAR = '\uFFFD';

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

        final char[] buf = TemporaryThreadLocals.get().charArray(toExcluded - from);
        int bufIdx = 0;
        for (int i = from; i < toExcluded;) {
            final char c = s.charAt(i++);
            if (c != '%') {
                buf[bufIdx++] = c != '+' ? c : SPACE;
                continue;
            }

            // %x
            if (i + 2 > toExcluded) {
                buf[bufIdx++] = UNKNOWN_CHAR;
                break;
            }

            // %xx
            final int b = decodeHexByte(s.charAt(i++), s.charAt(i++));
            if (b < 0) {
                buf[bufIdx++] = UNKNOWN_CHAR;
                continue;
            }

            if ((b & 0x80) == 0) {
                buf[bufIdx++] = (char) b;
                continue;
            }

            // 2-byte UTF-8
            if ((b >>> 5) == 0b110 && (b & 0x1E) != 0) {
                if (toExcluded - i < 3) {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    break;
                }

                if (s.charAt(i) != '%') {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    i += 3;
                    continue;
                }

                final int b2 = decodeHexByte(s.charAt(i + 1), s.charAt(i + 2));
                i += 3;

                if (b2 < 0 || !isContinuation(b2)) {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    continue;
                }

                buf[bufIdx++] = (char) (((byte) b << 6) ^ (byte) b2 ^
                                        ((byte) 0xC0 << 6) ^ (byte) 0x80);
                continue;
            }

            // 3-byte UTF-8
            if ((b >>> 4) == 0b1110) {
                if (toExcluded - i < 6) {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    break;
                }

                if (s.charAt(i) != '%' || s.charAt(i + 3) != '%') {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    i += 6;
                    continue;
                }

                final int b2 = decodeHexByte(s.charAt(i + 1), s.charAt(i + 2));
                final int b3 = decodeHexByte(s.charAt(i + 4), s.charAt(i + 5));
                i += 6;

                if (b2 < 0 || b3 < 0 ||
                    (b == 0xe0 && (b2 & 0xe0) == 0x80) || !isContinuation(b2) || !isContinuation(b3)) {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    continue;
                }

                final char decoded = (char) (((byte) b << 12) ^ ((byte) b2 << 6) ^ (byte) b3 ^
                                             ((byte) 0xE0 << 12) ^ ((byte) 0x80 << 6) ^ (byte) 0x80);
                buf[bufIdx++] = !Character.isSurrogate(decoded) ? decoded : UNKNOWN_CHAR;
                continue;
            }

            // 4-byte UTF-8
            if ((b >>> 3) == 0b11110) {
                if (toExcluded - i < 9) {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    break;
                }

                if (s.charAt(i) != '%' || s.charAt(i + 3) != '%' || s.charAt(i + 6) != '%') {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    i += 9;
                    continue;
                }

                final int b2 = decodeHexByte(s.charAt(i + 1), s.charAt(i + 2));
                final int b3 = decodeHexByte(s.charAt(i + 4), s.charAt(i + 5));
                final int b4 = decodeHexByte(s.charAt(i + 7), s.charAt(i + 8));
                i += 9;

                if (b2 < 0 || b3 < 0 || b4 < 0 ||
                    !isContinuation(b2) || !isContinuation(b3) || !isContinuation(b4)) {
                    buf[bufIdx++] = UNKNOWN_CHAR;
                    continue;
                }

                final int codepoint =
                        ((byte) b << 18) ^ ((byte) b2 << 12) ^ ((byte) b3 << 6) ^ (byte) b4 ^
                        ((byte) 0xF0 << 18) ^ ((byte) 0x80 << 12) ^ ((byte) 0x80 << 6) ^ (byte) 0x80;
                buf[bufIdx++] = Character.highSurrogate(codepoint);
                buf[bufIdx++] = Character.lowSurrogate(codepoint);
                continue;
            }

            buf[bufIdx++] = UNKNOWN_CHAR;
        }

        return new String(buf, 0, bufIdx);
    }

    private static int decodeHexByte(char c1, char c2) {
        final int hi = decodeHexNibble(c1);
        final int lo = decodeHexNibble(c2);
        if (hi < 0 || lo < 0) {
            return -1;
        } else {
            return (hi << 4) + lo;
        }
    }

    private static boolean isContinuation(int b) {
        return (b & 0xc0) == 0x80;
    }

    private QueryStringDecoder() {}
}
