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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.internal.StringUtil;

final class QueryStringEncoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/bd8cea644a07890f5bada18ddff0a849b58cd861/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringEncoder.java

    private static final byte WRITE_UTF_UNKNOWN = (byte) '?';
    private static final char[] CHAR_MAP = "0123456789ABCDEF".toCharArray();

    static void encodeParams(StringBuilder buf, QueryParamGetters params) {
        for (Entry<String, String> e : params) {
            encodeUtf8Component(buf, e.getKey());
            final String value = e.getValue();
            if (value != null) {
                buf.append('=');
                encodeUtf8Component(buf, value);
            }
            buf.append('&');
        }

        buf.setLength(buf.length() - 1);
    }

    /**
     * Encodes a query component (name or value).
     *
     * @see ByteBufUtil#writeUtf8(ByteBuf, CharSequence, int, int)
     */
    private static void encodeUtf8Component(StringBuilder uriBuilder, String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            final char c = s.charAt(i);
            if (c < 0x80) {
                if (dontNeedEncoding(c)) {
                    uriBuilder.append(c);
                } else {
                    appendEncoded(uriBuilder, c);
                }
            } else if (c < 0x800) {
                appendEncoded(uriBuilder, 0xc0 | (c >> 6));
                appendEncoded(uriBuilder, 0x80 | (c & 0x3f));
            } else if (StringUtil.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c)) {
                    appendEncoded(uriBuilder, WRITE_UTF_UNKNOWN);
                    continue;
                }
                // Surrogate Pair consumes 2 characters.
                if (++i == s.length()) {
                    appendEncoded(uriBuilder, WRITE_UTF_UNKNOWN);
                    break;
                }
                // Extra method to allow inlining the rest of writeUtf8 which is the most likely code path.
                writeUtf8Surrogate(uriBuilder, c, s.charAt(i));
            } else {
                appendEncoded(uriBuilder, 0xe0 | (c >> 12));
                appendEncoded(uriBuilder, 0x80 | ((c >> 6) & 0x3f));
                appendEncoded(uriBuilder, 0x80 | (c & 0x3f));
            }
        }
    }

    private static void writeUtf8Surrogate(StringBuilder uriBuilder, char c, char c2) {
        if (!Character.isLowSurrogate(c2)) {
            appendEncoded(uriBuilder, WRITE_UTF_UNKNOWN);
            appendEncoded(uriBuilder, Character.isHighSurrogate(c2) ? WRITE_UTF_UNKNOWN : c2);
            return;
        }
        final int codePoint = Character.toCodePoint(c, c2);
        // See http://www.unicode.org/versions/Unicode7.0.0/ch03.pdf#G2630.
        appendEncoded(uriBuilder, 0xf0 | (codePoint >> 18));
        appendEncoded(uriBuilder, 0x80 | ((codePoint >> 12) & 0x3f));
        appendEncoded(uriBuilder, 0x80 | ((codePoint >> 6) & 0x3f));
        appendEncoded(uriBuilder, 0x80 | (codePoint & 0x3f));
    }

    private static void appendEncoded(StringBuilder uriBuilder, int b) {
        uriBuilder.append('%').append(forDigit(b >> 4)).append(forDigit(b));
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

    /**
     * Determines whether the given character is a unreserved character.
     *
     * <p>unreserved characters do not need to be encoded, and include uppercase and lowercase
     * letters, decimal digits, hyphen, period, underscore, and tilde.</p>
     *
     * <p>unreserved  = ALPHA / DIGIT / "-" / "_" / "." / "*"</p>
     *
     * @param ch the char to be judged whether it need to be encode
     * @return true or false
     */
    private static boolean dontNeedEncoding(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' ||
               ch == '-' || ch == '_' || ch == '.' || ch == '*';
    }

    private QueryStringEncoder() {}
}
