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
 * Copyright 2015 The Netty Project
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

import static java.util.Objects.requireNonNull;

import java.nio.CharBuffer;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.util.internal.InternalThreadLocalMap;

final class CookieUtil {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/5d448377e94ca1eca3ec994d34a1170912e57ae9/codec-http/src/main/java/io/netty/handler/codec/http/cookie/CookieUtil.java

    private static final BitSet VALID_COOKIE_NAME_OCTETS = validCookieNameOctets();

    private static final BitSet VALID_COOKIE_VALUE_OCTETS = validCookieValueOctets();

    // token = 1*<any CHAR except CTLs or separators>
    // separators = "(" | ")" | "<" | ">" | "@"
    // | "," | ";" | ":" | "\" | <">
    // | "/" | "[" | "]" | "?" | "="
    // | "{" | "}" | SP | HT
    private static BitSet validCookieNameOctets() {
        final BitSet bits = new BitSet();
        for (int i = 32; i < 127; i++) {
            bits.set(i);
        }
        final int[] separators = { '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"',
                                   '/', '[', ']', '?', '=', '{', '}', ' ', '\t' };
        for (int separator : separators) {
            bits.set(separator, false);
        }
        return bits;
    }

    // cookie-octet = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
    // US-ASCII characters excluding CTLs, whitespace, DQUOTE, comma, semicolon, and backslash
    private static BitSet validCookieValueOctets() {
        final BitSet bits = new BitSet();
        bits.set(0x21);
        for (int i = 0x23; i <= 0x2B; i++) {
            bits.set(i);
        }
        for (int i = 0x2D; i <= 0x3A; i++) {
            bits.set(i);
        }
        for (int i = 0x3C; i <= 0x5B; i++) {
            bits.set(i);
        }
        for (int i = 0x5D; i <= 0x7E; i++) {
            bits.set(i);
        }
        return bits;
    }

    static StringBuilder stringBuilder() {
        return InternalThreadLocalMap.get().stringBuilder();
    }

    /**
     * Strips out the trailing 2-char separator from the specified {@link StringBuilder}.
     *
     * @param buf a buffer where some cookies were encoded.
     * @return the {@link String} without the trailing separator
     */
    static String stripTrailingSeparator(StringBuilder buf) {
        if (buf.length() > 0) {
            buf.setLength(buf.length() - 2);
        }
        return buf.toString();
    }

    static void add(StringBuilder sb, String name, long val) {
        sb.append(name);
        sb.append('=');
        sb.append(val);
        sb.append(';');
        sb.append(HttpConstants.SP_CHAR);
    }

    static void add(StringBuilder sb, String name, String val) {
        sb.append(name);
        sb.append('=');
        sb.append(val);
        sb.append(';');
        sb.append(HttpConstants.SP_CHAR);
    }

    static void add(StringBuilder sb, String name) {
        sb.append(name);
        sb.append(';');
        sb.append(HttpConstants.SP_CHAR);
    }

    static void addQuoted(StringBuilder sb, String name, @Nullable String val) {
        if (val == null) {
            val = "";
        }

        sb.append(name);
        sb.append('=');
        sb.append('"');
        sb.append(val);
        sb.append('"');
        sb.append(';');
        sb.append(HttpConstants.SP_CHAR);
    }

    private static int firstInvalidCookieNameOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_NAME_OCTETS);
    }

    private static int firstInvalidCookieValueOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_VALUE_OCTETS);
    }

    static int firstInvalidOctet(CharSequence cs, BitSet bits) {
        for (int i = 0; i < cs.length(); i++) {
            final char c = cs.charAt(i);
            if (!bits.get(c)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static CharSequence unwrapValue(CharSequence cs) {
        final int len = cs.length();
        if (len > 0 && cs.charAt(0) == '"') {
            if (len >= 2 && cs.charAt(len - 1) == '"') {
                // properly balanced
                return len == 2 ? "" : cs.subSequence(1, len - 1);
            } else {
                return null;
            }
        }
        return cs;
    }

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/4c709be1abf6e52c6a5640c1672d259f1de638d1/codec-http/src/main/java/io/netty/handler/codec/http/cookie/CookieEncoder.java

    static void validateCookie(boolean strict, String name, String value) {
        if (strict) {
            int pos;

            if ((pos = firstInvalidCookieNameOctet(name)) >= 0) {
                throw new IllegalArgumentException("Cookie name contains an invalid char: " + name.charAt(pos));
            }

            final CharSequence unwrappedValue = unwrapValue(value);
            if (unwrappedValue == null) {
                throw new IllegalArgumentException("Cookie value wrapping quotes are not balanced: " + value);
            }

            if ((pos = firstInvalidCookieValueOctet(unwrappedValue)) >= 0) {
                throw new IllegalArgumentException("Cookie value contains an invalid char: " +
                                                   unwrappedValue.charAt(pos));
            }
        }
    }

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/97d871a7553a01384b43df855dccdda5205ae77a/codec-http/src/main/java/io/netty/handler/codec/http/cookie/CookieDecoder.java

    @Nullable
    static CookieBuilder initCookie(Logger logger, boolean strict,
                                    String header, int nameBegin, int nameEnd, int valueBegin, int valueEnd) {
        if (nameBegin == -1 || nameBegin == nameEnd) {
            logger.debug("Skipping cookie with null name");
            return null;
        }

        if (valueBegin == -1) {
            logger.debug("Skipping cookie with null value");
            return null;
        }

        final CharSequence wrappedValue = CharBuffer.wrap(header, valueBegin, valueEnd);
        final CharSequence unwrappedValue = unwrapValue(wrappedValue);
        if (unwrappedValue == null) {
            logger.debug("Skipping cookie because starting quotes are not properly balanced in '{}'",
                         wrappedValue);
            return null;
        }

        final String name = header.substring(nameBegin, nameEnd);

        int invalidOctetPos;
        if (strict && (invalidOctetPos = firstInvalidCookieNameOctet(name)) >= 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie because name '{}' contains invalid char '{}'",
                             name, name.charAt(invalidOctetPos));
            }
            return null;
        }

        final boolean valueQuoted = unwrappedValue.length() != valueEnd - valueBegin;

        if (strict && (invalidOctetPos = firstInvalidCookieValueOctet(unwrappedValue)) >= 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie because value '{}' contains invalid char '{}'",
                             unwrappedValue, unwrappedValue.charAt(invalidOctetPos));
            }
            return null;
        }

        return Cookie.builder(name, unwrappedValue.toString()).valueQuoted(valueQuoted);
    }

    // The methods newly added in the fork.
    static Cookies fromCookieHeaders(ImmutableSet.Builder<Cookie> builder,
                                     boolean strict, Iterator<String> it) {
        assert it.hasNext();
        do {
            final String v = it.next();
            requireNonNull(v, "cookieHeaders contains null.");
            final Cookies cookies = Cookie.fromCookieHeader(strict, v);
            builder.addAll(cookies);
        } while (it.hasNext());

        return Cookies.of(builder.build());
    }

    static Cookies fromSetCookieHeaders(ImmutableSet.Builder<Cookie> builder,
                                        boolean strict, Iterator<String> it) {
        assert it.hasNext();
        do {
            final String v = it.next();
            requireNonNull(v, "setCookieHeaders contains null.");
            final Cookie cookie = Cookie.fromSetCookieHeader(strict, v);
            if (cookie != null) {
                builder.add(cookie);
            }
        } while (it.hasNext());

        return Cookies.of(builder.build());
    }

    static List<String> toSetCookieHeaders(ImmutableList.Builder<String> builder,
                                           boolean strict, Iterator<? extends Cookie> it) {
        assert it.hasNext();
        do {
            final Cookie c = it.next();
            requireNonNull(c, "cookies contains null.");
            builder.add(c.toSetCookieHeader(strict));
        } while (it.hasNext());

        return builder.build();
    }

    private CookieUtil() {}
}
