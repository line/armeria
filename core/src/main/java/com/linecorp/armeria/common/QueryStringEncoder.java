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

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.internal.TemporaryThreadLocals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.internal.StringUtil;

final class QueryStringEncoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/bd8cea644a07890f5bada18ddff0a849b58cd861/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringEncoder.java

    private static final byte WRITE_UTF_UNKNOWN = (byte) '?';
    private static final char[] CHAR_MAP = "0123456789ABCDEF".toCharArray();
    private static final boolean[] SAFE_OCTETS =
            createSafeOctets("-_.*abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");

    private static boolean[] createSafeOctets(String safeChars) {
        int maxChar = -1;
        for (int i = 0; i < safeChars.length(); i++) {
            maxChar = Math.max(safeChars.charAt(i), maxChar);
        }
        final boolean[] octets = new boolean[maxChar + 1];
        for (int i = 0; i < safeChars.length(); i++) {
            octets[safeChars.charAt(i)] = true;
        }
        return octets;
    }

    static void encodeParams(TemporaryThreadLocals tempThreadLocals,
                             StringBuilder buf, QueryParamGetters params) {
        for (Entry<String, String> e : params) {
            final String name = e.getKey();
            if (isSafeOctetsOnly(name)) {
                buf.append(name);
            } else {
                encodeUtf8Component(tempThreadLocals, buf, name);
            }
            buf.append('=');

            final String value = e.getValue();
            if (isSafeOctetsOnly(value)) {
                buf.append(value);
            } else {
                encodeUtf8Component(tempThreadLocals, buf, value);
            }
            buf.append('&');
        }

        buf.setLength(buf.length() - 1);
    }

    private static boolean isSafeOctetsOnly(String s) {
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            if (!isSafeOctet(s.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSafeOctet(char c) {
        return c < SAFE_OCTETS.length && SAFE_OCTETS[c];
    }

    /**
     * Encodes a query component (name or value).
     *
     * @see ByteBufUtil#writeUtf8(ByteBuf, CharSequence, int, int)
     */
    @VisibleForTesting
    static void encodeUtf8Component(TemporaryThreadLocals tempThreadLocals,
                                            StringBuilder buf, String s) {
        final char[] tmp = tempThreadLocals.charArray(12);
        int safeOctetStart = 0;
        final int len = s.length();
        for (int i = 0; i < len;) {
            final char c = s.charAt(i);
            if (c < 0x80) {
                if (!isSafeOctet(c)) {
                    if (i > safeOctetStart) {
                        buf.append(s, safeOctetStart, i);
                    }
                    safeOctetStart = ++i;

                    if (c == ' ') {
                        buf.append('+');
                    } else {
                        appendEncoded(buf, tmp, c);
                    }
                } else {
                    i++;
                }
                continue;
            }

            if (i > safeOctetStart) {
                buf.append(s, safeOctetStart, i);
            }
            safeOctetStart = ++i;

            if (c < 0x800) {
                appendEncoded(buf, tmp,
                              0xc0 | (c >> 6), 0x80 | (c & 0x3f));
                continue;
            }

            if (!StringUtil.isSurrogate(c)) {
                appendEncoded(buf, tmp,
                              0xe0 | (c >> 12),
                              0x80 | ((c >> 6) & 0x3f),
                              0x80 | (c & 0x3f));
                continue;
            }

            if (!Character.isHighSurrogate(c)) {
                appendEncoded(buf, tmp, WRITE_UTF_UNKNOWN);
                continue;
            }

            // Surrogate Pair consumes 2 characters.
            if (i == len) {
                appendEncoded(buf, tmp, WRITE_UTF_UNKNOWN);
                return;
            }

            // Extra method to allow inlining the rest of writeUtf8 which is the most likely code path.
            writeUtf8Surrogate(buf, tmp, c, s.charAt(i));
            safeOctetStart = ++i;
        }

        if (safeOctetStart < len) {
            buf.append(s, safeOctetStart, len);
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
        tmp[0] = '%';
        tmp[1] = forDigit(b >>> 4);
        tmp[2] = forDigit(b);
    }

    private static void setEncoded(char[] tmp, int b1, int b2) {
        setEncoded(tmp, b1);
        tmp[3] = '%';
        tmp[4] = forDigit(b2 >>> 4);
        tmp[5] = forDigit(b2);
    }

    private static void setEncoded(char[] tmp, int b1, int b2, int b3) {
        setEncoded(tmp, b1, b2);
        tmp[6] = '%';
        tmp[7] = forDigit(b3 >>> 4);
        tmp[8] = forDigit(b3);
    }

    private static void setEncoded(char[] tmp, int b1, int b2, int b3, int b4) {
        setEncoded(tmp, b1, b2, b3);
        tmp[9] = '%';
        tmp[10] = forDigit(b4 >>> 4);
        tmp[11] = forDigit(b4);
    }

    /**
     * Convert the given digit to a upper hexadecimal char.
     *
     * @param digit the number to convert to a character.
     * @return the {@code char} representation of the specified digit
     *         in hexadecimal.
     */
    private static char forDigit(int digit) {
        return CHAR_MAP[digit & 0xF];
    }

    private QueryStringEncoder() {}
}
