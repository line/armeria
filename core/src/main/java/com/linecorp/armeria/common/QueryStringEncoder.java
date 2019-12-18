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

import com.linecorp.armeria.internal.TemporaryThreadLocals;

import io.netty.util.internal.StringUtil;

final class QueryStringEncoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/bd8cea644a07890f5bada18ddff0a849b58cd861/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringEncoder.java

    private static final byte WRITE_UTF_UNKNOWN = (byte) '?';
    private static final char[] CHAR_MAP = "0123456789ABCDEF".toCharArray();
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

    static void encodeParams(TemporaryThreadLocals tempThreadLocals,
                             StringBuilder buf, QueryParamGetters params) {
        for (Entry<String, String> e : params) {
            encodeComponent(tempThreadLocals, buf, e.getKey()).append('=');
            encodeComponent(tempThreadLocals, buf, e.getValue()).append('&');
        }

        buf.setLength(buf.length() - 1);
    }

    private static StringBuilder encodeComponent(TemporaryThreadLocals tempThreadLocals, StringBuilder buf, String s) {
        final int firstUnsafeOctetIdx = indexOfUnsafeOctet(s, 0);
        if (firstUnsafeOctetIdx < 0) {
            buf.append(s);
        } else {
            if (firstUnsafeOctetIdx != 0) {
                buf.append(s, 0, firstUnsafeOctetIdx);
            }

            encodeUtf8Component(tempThreadLocals, buf, s, firstUnsafeOctetIdx);
        }
        return buf;
    }

    private static int indexOfUnsafeOctet(String s, int start) {
        final int len = s.length();
        for (int i = start; i < len; i++) {
            if (!isSafeOctet(s.charAt(i))) {
                return i;
            }
        }

        return -1;
    }

    private static boolean isSafeOctet(char c) {
        return c < SAFE_OCTETS.length && SAFE_OCTETS[c] != 0;
    }

    /**
     * Encodes a query component (name or value). The octet at {@code start} must be an unsafe octet.
     */
    private static void encodeUtf8Component(TemporaryThreadLocals tempThreadLocals,
                                            StringBuilder buf, String s, int start) {
        final int end = s.length();
        if (start == end) {
            return;
        }

        final char[] tmp = tempThreadLocals.charArray(12);
        tmp[0] = tmp[3] = tmp[6] = tmp[9] = '%'; // Pre-set '%' so we don't have to set anymore.

        int i = start;
        for (;;) {
            final char c = s.charAt(i++);
            if (c < 0x80) {
                if (c == ' ') {
                    buf.append('+');
                } else {
                    appendEncoded(buf, tmp, c);
                }
            } else if (c < 0x800) {
                appendEncoded(buf, tmp,
                              0xc0 | (c >> 6), 0x80 | (c & 0x3f));
            } else if (!StringUtil.isSurrogate(c)) {
                appendEncoded(buf, tmp,
                              0xe0 | (c >> 12),
                              0x80 | ((c >> 6) & 0x3f),
                              0x80 | (c & 0x3f));
            } else if (!Character.isHighSurrogate(c)) {
                appendEncoded(buf, tmp, WRITE_UTF_UNKNOWN);
            } else if (i == end) { // Surrogate Pair consumes 2 characters.
                appendEncoded(buf, tmp, WRITE_UTF_UNKNOWN);
                break;
            } else {
                // Extra method to allow inlining the rest of writeUtf8 which is the most likely code path.
                writeUtf8Surrogate(buf, tmp, c, s.charAt(i++));
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

    private static void writeUtf8Surrogate(StringBuilder buf, char[] tmp, char c, char c2) {
        if (!Character.isLowSurrogate(c2)) {
            appendEncoded(buf, tmp,
                          WRITE_UTF_UNKNOWN,
                          Character.isHighSurrogate(c2) ? WRITE_UTF_UNKNOWN : c2);
            return;
        }

        final int codePoint = Character.toCodePoint(c, c2);
        // See http://www.unicode.org/versions/Unicode7.0.0/ch03.pdf#G2630.
        appendEncoded(buf, tmp,
                      0xf0 | (codePoint >> 18),
                      0x80 | ((codePoint >> 12) & 0x3f),
                      0x80 | ((codePoint >> 6) & 0x3f),
                      0x80 | (codePoint & 0x3f));
    }

    private static void appendEncoded(StringBuilder buf, char[] tmp, int b) {
        setEncoded(tmp, b);
        buf.append(tmp, 0, 3);
    }

    private static void appendEncoded(StringBuilder buf, char[] tmp, int b1, int b2) {
        setEncoded(tmp, b1, b2);
        buf.append(tmp, 0, 6);
    }

    private static void appendEncoded(StringBuilder buf, char[] tmp, int b1, int b2, int b3) {
        setEncoded(tmp, b1, b2, b3);
        buf.append(tmp, 0, 9);
    }

    private static void appendEncoded(StringBuilder buf, char[] tmp, int b1, int b2, int b3, int b4) {
        setEncoded(tmp, b1, b2, b3, b4);
        buf.append(tmp, 0, 12);
    }

    private static void setEncoded(char[] tmp, int b) {
        tmp[1] = highDigit(b);
        tmp[2] = lowDigit(b);
    }

    private static void setEncoded(char[] tmp, int b1, int b2) {
        setEncoded(tmp, b1);
        tmp[4] = highDigit(b2);
        tmp[5] = lowDigit(b2);
    }

    private static void setEncoded(char[] tmp, int b1, int b2, int b3) {
        setEncoded(tmp, b1, b2);
        tmp[7] = highDigit(b3);
        tmp[8] = lowDigit(b3);
    }

    private static void setEncoded(char[] tmp, int b1, int b2, int b3, int b4) {
        setEncoded(tmp, b1, b2, b3);
        tmp[10] = highDigit(b4);
        tmp[11] = lowDigit(b4);
    }

    /**
     * Convert the given digit to a upper hexadecimal char.
     *
     * @param digit the number to convert to a character.
     * @return the {@code char} representation of the specified digit
     *         in hexadecimal.
     */
    private static char highDigit(int digit) {
        return CHAR_MAP[digit >>> 4];
    }
    private static char lowDigit(int digit) {
        return CHAR_MAP[digit & 0xF];
    }

    private QueryStringEncoder() {}
}
