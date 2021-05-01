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

package com.linecorp.armeria.internal.common;

import static io.netty.util.internal.StringUtil.SPACE;

import java.util.Arrays;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

public final class PercentDecoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/7d6d953153697bd66c3b01ca8ec73c4494a81788/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringDecoder.java

    @SuppressWarnings("checkstyle:AvoidEscapedUnicodeCharacters")
    private static final char UNKNOWN_CHAR = '\uFFFD';
    private static final byte[] OCTETS_TO_HEX = new byte[Character.MAX_VALUE + 1];

    static {
        Arrays.fill(OCTETS_TO_HEX, (byte) -1);
        for (int i = '0'; i <= '9'; i++) {
            OCTETS_TO_HEX[i] = (byte) (i - '0');
        }
        for (int i = 'A'; i <= 'F'; i++) {
            OCTETS_TO_HEX[i] = (byte) (i - 'A' + 10);
        }
        for (int i = 'a'; i <= 'f'; i++) {
            OCTETS_TO_HEX[i] = (byte) (i - 'a' + 10);
        }
    }

    /**
     * Decodes the specified string if it's
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-2.1">Percent-Encoded</a>.
     */
    public static String decodeComponent(String s) {
        return decodeComponent(TemporaryThreadLocals.get(), s, 0, s.length());
    }

    /**
     * Decodes the specified string from the index of {@code from} to the index of {@code toExcluded} if it's
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-2.1">Percent-Encoded</a>.
     */
    public static String decodeComponent(TemporaryThreadLocals tempThreadLocals,
                                         String s, int from, int toExcluded) {
        if (from == toExcluded) {
            return "";
        }

        for (int i = from; i < toExcluded; i++) {
            final char c = s.charAt(i);
            if ((c & 0xFFF1) != 0x21) {
                // We can skip with a single comparison because both
                // '%' (0b00100101) and '+' (0b00101011) has the same five bits (0b0010xxx1).
                continue;
            }

            // At this point, `c` is one of the following characters: # % ' ) + - /
            if (c == '%' || c == '+') {
                final String decoded = decodeUtf8Component(tempThreadLocals.charArray(toExcluded - from), s,
                                                           from, toExcluded);
                tempThreadLocals.releaseCharArray();
                return decoded;
            }
        }

        return s.substring(from, toExcluded);
    }

    private static String decodeUtf8Component(char[] buf, String s, int from, int toExcluded) {
        int bufIdx = 0;
        for (int i = from; i < toExcluded;) {
            final int undecodedChars = toExcluded - i;
            final char c = s.charAt(i++);
            if (c != '%') {
                buf[bufIdx++] = c != '+' ? c : SPACE;
                continue;
            }

            // %x or %
            if (undecodedChars < 3) {
                buf[bufIdx++] = UNKNOWN_CHAR;
                break;
            }

            // %xx
            final int b = decodeHexByte(s.charAt(i++), s.charAt(i++));
            if (b < 0) {
                buf[bufIdx++] = UNKNOWN_CHAR;
                continue;
            }

            // 1-byte ASCII
            if ((b & 0x80) == 0) {
                buf[bufIdx++] = (char) b;
                continue;
            }

            // 2-byte UTF-8
            if ((b >>> 5) == 0b110 && (b & 0x1E) != 0) {
                if (undecodedChars < 6 || s.charAt(i) != '%') {
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
                if (undecodedChars < 9 || s.charAt(i) != '%' || s.charAt(i + 3) != '%') {
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
                if (undecodedChars < 12 ||
                    s.charAt(i) != '%' || s.charAt(i + 3) != '%' || s.charAt(i + 6) != '%') {
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
        final int hi = OCTETS_TO_HEX[c1];
        final int lo = OCTETS_TO_HEX[c2];
        return (hi << 4) | lo;
    }

    private static boolean isContinuation(int b) {
        return (b & 0xc0) == 0x80;
    }

    private PercentDecoder() {}
}
