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
/*
 * Copyright (C) 2008 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.internal.common;

import io.netty.util.internal.StringUtil;

public final class PercentEncoder {

    // Forked from netty-4.1.43 and guava-28.1
    // https://github.com/netty/netty/blob/bd8cea644a07890f5bada18ddff0a849b58cd861/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringEncoder.java
    // https://github.com/google/guava/blob/13e39cd167a49aad525be462e61d9e5f2b1781ec/guava/src/com/google/common/net/PercentEscaper.java

    private static final char[] UTF_UNKNOWN = { '%', '3', 'F' }; // Percent encoded question mark
    private static final char[] UPPER_HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    private static final byte[] SAFE_OCTETS = new byte[Character.MAX_VALUE + 1];

    static {
        // Unreserved characters with '*' because most browsers such as Chrome and Firefox do not encode '*'.
        // See https://datatracker.ietf.org/doc/html/rfc3986#section-2.3
        final String safeOctetStr = "-_.~*abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < safeOctetStr.length(); i++) {
            SAFE_OCTETS[safeOctetStr.charAt(i)] = -1;
        }
    }

    /**
     * Encodes the specified string using
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-2.1">Percent-Encoding</a> and appends it to the
     * specified {@link StringBuilder}.
     */
    public static void encodeComponent(StringBuilder buf, String s) {
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            final char c = s.charAt(i);
            if (SAFE_OCTETS[c] == 0) {
                if (i != 0) {
                    buf.append(s, 0, i);
                }
                encodeUtf8Component(buf, s, i);
                return;
            }
        }
        buf.append(s);
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
            } else if (!Character.isHighSurrogate(c) || i == end) {
                buf.append(UTF_UNKNOWN);
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
            if (SAFE_OCTETS[c] == 0) {
                return i;
            }
        }

        return -1;
    }

    private PercentEncoder() {}
}
