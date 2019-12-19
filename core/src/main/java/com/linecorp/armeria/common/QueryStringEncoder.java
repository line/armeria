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

import java.util.Map.Entry;

import io.netty.util.internal.StringUtil;

final class QueryStringEncoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/bd8cea644a07890f5bada18ddff0a849b58cd861/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringEncoder.java

    private static final char[] UTF_UNKNOWN = { '%', '3', 'F' }; // Percent encoded question mark
    private static final char[] UPPER_HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    private static final byte[] SAFE_OCTETS =
            createSafeOctets("-_.*abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");

    private static byte[] createSafeOctets(String safeChars) {
        int maxChar = -1;
        for (int i = 0; i < safeChars.length(); i++) {
            maxChar = Math.max(safeChars.charAt(i), maxChar);
        }
        final byte[] octets = new byte[maxChar + 1];
        for (int i = 0; i < safeChars.length(); i++) {
            octets[safeChars.charAt(i)] = -1;
        }
        return octets;
    }

    static void encodeParams(StringBuilder buf, QueryParamGetters params) {
        for (Entry<String, String> e : params) {
            encodeComponent(buf, e.getKey()).append('=');
            encodeComponent(buf, e.getValue()).append('&');
        }

        buf.setLength(buf.length() - 1);
    }

    private static StringBuilder encodeComponent(StringBuilder buf, String s) {
        final int firstUnsafeOctetIdx = indexOfUnsafeOctet(s, 0);
        if (firstUnsafeOctetIdx < 0) {
            buf.append(s);
        } else {
            if (firstUnsafeOctetIdx != 0) {
                buf.append(s, 0, firstUnsafeOctetIdx);
            }

            encodeUtf8Component(buf, s, firstUnsafeOctetIdx);
        }
        return buf;
    }

    /**
     * Encodes a query component (name or value). The octet at {@code start} must be an unsafe octet.
     */
    private static void encodeUtf8Component(StringBuilder buf, String s, int start) {
        final int end = s.length();
        if (start == end) {
            return;
        }

        final char[] tmp = new char[12];
        tmp[0] = tmp[3] = tmp[6] = tmp[9] = '%'; // Pre-set '%' so we don't have to set anymore.

        int i = start;
        for (;;) {
            char c = s.charAt(i++);
            if (c < 0x80) {
                if (c == ' ') {
                    buf.append('+');
                } else {
                    tmp[2] = UPPER_HEX_DIGITS[c & 0xF];
                    tmp[1] = UPPER_HEX_DIGITS[c >>> 4];
                    buf.append(tmp, 0, 3);
                }
            } else if (c < 0x800) {
                tmp[5] = UPPER_HEX_DIGITS[c & 0xF];
                c >>>= 4;
                tmp[4] = UPPER_HEX_DIGITS[0x8 | (c & 0x3)];
                c >>>= 2;
                tmp[2] = UPPER_HEX_DIGITS[c & 0xF];
                tmp[1] = UPPER_HEX_DIGITS[0xC | (c >>> 4)];
                buf.append(tmp, 0, 6);
            } else if (!StringUtil.isSurrogate(c)) {
                tmp[8] = UPPER_HEX_DIGITS[c & 0xF];
                c >>>= 4;
                tmp[7] = UPPER_HEX_DIGITS[0x8 | (c & 0x3)];
                c >>>= 2;
                tmp[5] = UPPER_HEX_DIGITS[c & 0xF];
                c >>>= 4;
                tmp[4] = UPPER_HEX_DIGITS[0x8 | (c & 0x3)];
                tmp[2] = UPPER_HEX_DIGITS[(c >>> 2) & 0xF];
                tmp[1] = 'E';
                buf.append(tmp, 0, 9);
            } else if (!Character.isHighSurrogate(c)) {
                buf.append(UTF_UNKNOWN);
            } else if (i == end) { // Surrogate Pair consumes 2 characters.
                buf.append(UTF_UNKNOWN);
                break;
            } else {
                final char c2 = s.charAt(i++);
                if (!Character.isLowSurrogate(c2)) {
                    buf.append(UTF_UNKNOWN);
                    if (Character.isHighSurrogate(c2)) {
                        buf.append(UTF_UNKNOWN);
                    } else {
                        tmp[2] = UPPER_HEX_DIGITS[c2 & 0xF];
                        tmp[1] = UPPER_HEX_DIGITS[c2 >>> 4];
                        buf.append(tmp, 0, 3);
                    }
                } else {
                    int codePoint = Character.toCodePoint(c, c2);
                    // See http://www.unicode.org/versions/Unicode7.0.0/ch03.pdf#G2630.
                    tmp[11] = UPPER_HEX_DIGITS[codePoint & 0xF];
                    codePoint >>>= 4;
                    tmp[10] = UPPER_HEX_DIGITS[0x8 | (codePoint & 0x3)];
                    codePoint >>>= 2;
                    tmp[8] = UPPER_HEX_DIGITS[codePoint & 0xF];
                    codePoint >>>= 4;
                    tmp[7] = UPPER_HEX_DIGITS[0x8 | (codePoint & 0x3)];
                    codePoint >>>= 2;
                    tmp[5] = UPPER_HEX_DIGITS[codePoint & 0xF];
                    codePoint >>>= 4;
                    tmp[4] = UPPER_HEX_DIGITS[0x8 | (codePoint & 0x3)];
                    tmp[2] = UPPER_HEX_DIGITS[(codePoint >>> 2) & 0xF];
                    tmp[1] = 'F';
                    buf.append(tmp, 0, 12);
                }
            }

            // Find and append the safe region as-is.
            final int nextUnsafeOctetIndex = indexOfUnsafeOctet(s, i);
            if (nextUnsafeOctetIndex < 0) {
                if (i != end) {
                    buf.append(s, i, end);
                }
                break;
            }

            if (nextUnsafeOctetIndex != i) {
                buf.append(s, i, nextUnsafeOctetIndex);
                i = nextUnsafeOctetIndex;
            }
        }
    }

    private static int indexOfUnsafeOctet(String s, int start) {
        final int len = s.length();
        for (int i = start; i < len; i++) {
            final char c = s.charAt(i);
            if (c >= SAFE_OCTETS.length || SAFE_OCTETS[c] == 0) {
                return i;
            }
        }

        return -1;
    }

    private QueryStringEncoder() {}
}
