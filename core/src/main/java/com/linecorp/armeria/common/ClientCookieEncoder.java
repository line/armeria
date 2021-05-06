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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.CookieUtil.add;
import static com.linecorp.armeria.common.CookieUtil.addQuoted;
import static com.linecorp.armeria.common.CookieUtil.stringBuilder;
import static com.linecorp.armeria.common.CookieUtil.stripTrailingSeparator;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import io.netty.util.internal.InternalThreadLocalMap;

/**
 * A <a href="https://datatracker.ietf.org/doc/rfc6265/">RFC 6265</a> compliant cookie encoder for client side.
 *
 * <p>Note that multiple cookies are supposed to be sent at once in a single {@code "Cookie"} header.</p>
 *
 * @see ClientCookieDecoder
 */
final class ClientCookieEncoder {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/0623c6c5334bf43299e835cfcf86bfda19e2d4ce/codec-http/src/main/java/io/netty/handler/codec/http/ClientCookieEncoder.java

    private static final Cookie[] EMPTY_COOKIES = new Cookie[0];

    /**
     * Encodes the specified {@link Cookie} into a single {@code "Cookie"} header value.
     *
     * @param strict whether to validate that name and value chars are in the valid scope.
     * @param cookie the {@link Cookie} to encode.
     * @return a RFC 6265-style {@code "Cookie"} header value.
     */
    static String encode(boolean strict, Cookie cookie) {
        requireNonNull(cookie, "cookie");
        final StringBuilder buf = stringBuilder();
        encode(strict, buf, cookie);
        return stripTrailingSeparator(buf);
    }

    /**
     * Sort cookies into decreasing order of path length, breaking ties by sorting into increasing chronological
     * order of creation time, as recommended by RFC 6265.
     */
    private static final Comparator<Cookie> COOKIE_COMPARATOR = (c1, c2) -> {
        final String path1 = c1.path();
        final String path2 = c2.path();
        // Cookies with unspecified path default to the path of the request. We don't
        // know the request path here, but we assume that the length of an unspecified
        // path is longer than any specified path (i.e. pathless cookies come first),
        // because setting cookies with a path longer than the request path is of
        // limited use.
        final int len1 = path1 == null ? Integer.MAX_VALUE : path1.length();
        final int len2 = path2 == null ? Integer.MAX_VALUE : path2.length();
        final int res = Integer.compare(len2, len1);
        if (res != 0) {
            return res;
        }
        // Rely on Java's sort stability to retain creation order in cases where
        // cookies have same path length.
        return -1;
    };

    /**
     * Encodes the specified cookies into a single {@code "Cookie"} header value.
     *
     * @param strict whether to validate that name and value chars are in the valid scope and
     *               to sort the cookies into order of decreasing path length, as specified in RFC 6265.
     *               If {@code false}, the cookies are encoded in the order in which they are given.
     * @param cookies the {@link Cookie}s to encode.
     * @return a RFC 6265-style {@code "Cookie"} header value.
     */
    static String encode(boolean strict, Cookie... cookies) {
        assert cookies.length != 0 : cookies.length;

        final StringBuilder buf = stringBuilder();
        if (strict) {
            if (cookies.length == 1) {
                encode(true, buf, cookies[0]);
            } else {
                final Cookie[] cookiesSorted = Arrays.copyOf(cookies, cookies.length);
                Arrays.sort(cookiesSorted, COOKIE_COMPARATOR);
                for (Cookie c : cookiesSorted) {
                    encode(true, buf, c);
                }
            }
        } else {
            for (Cookie c : cookies) {
                encode(false, buf, c);
            }
        }
        return stripTrailingSeparator(buf);
    }

    /**
     * Encodes the specified cookies into a single {@code "Cookie"} header value.
     *
     * @param strict whether to validate that name and value chars are in the valid scope and
     *               to sort the cookies into order of decreasing path length, as specified in RFC 6265.
     *               If {@code false}, the cookies are encoded in the order in which they are given.
     * @param cookiesIt the {@link Iterator} of the {@link Cookie}s to encode.
     * @return a RFC 6265-style {@code "Cookie"} header value, or {@code null} if no cookies were specified.
     */
    static String encode(boolean strict, Iterator<? extends Cookie> cookiesIt) {
        assert cookiesIt.hasNext();

        final StringBuilder buf = stringBuilder();
        if (strict) {
            final Cookie firstCookie = cookiesIt.next();
            if (!cookiesIt.hasNext()) {
                encode(true, buf, firstCookie);
            } else {
                final List<Cookie> cookiesList = InternalThreadLocalMap.get().arrayList();
                cookiesList.add(firstCookie);
                while (cookiesIt.hasNext()) {
                    cookiesList.add(cookiesIt.next());
                }
                final Cookie[] cookiesSorted = cookiesList.toArray(EMPTY_COOKIES);
                Arrays.sort(cookiesSorted, COOKIE_COMPARATOR);
                for (Cookie c : cookiesSorted) {
                    encode(true, buf, c);
                }
            }
        } else {
            do {
                encode(false, buf, cookiesIt.next());
            } while (cookiesIt.hasNext());
        }
        return stripTrailingSeparator(buf);
    }

    private static void encode(boolean strict, StringBuilder buf, Cookie c) {
        final String name = c.name();
        final String value = firstNonNull(c.value(), "");

        CookieUtil.validateCookie(strict, name, value);

        if (c.isValueQuoted()) {
            addQuoted(buf, name, value);
        } else {
            add(buf, name, value);
        }
    }

    private ClientCookieEncoder() {}
}
