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
import static com.linecorp.armeria.common.CookieUtil.stripTrailingSeparatorOrNull;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import io.netty.util.internal.InternalThreadLocalMap;

/**
 * A <a href="http://tools.ietf.org/html/rfc6265">RFC6265</a> compliant cookie encoder for client side.
 *
 * <p>Note that multiple cookies are supposed to be sent at once in a single {@code "Cookie"} header.</p>
 *
 * <pre>{@code
 * RequestHeaders headers =
 *     RequestHeaders.of(HttpHeaderNames.COOKIE,
 *                       ClientCookieEncoder.strict().encode("JSESSIONID", "1234"));
 * }</pre>
 *
 * @see ClientCookieDecoder
 */
public final class ClientCookieEncoder extends CookieEncoder {

    // Forked from netty-4.1.43

    /**
     * Strict encoder that validates that name and value chars are in the valid scope and (for methods that
     * accept multiple cookies) sorts cookies into order of decreasing path length, as specified in RFC6265.
     */
    private static final ClientCookieEncoder STRICT = new ClientCookieEncoder(true);

    /**
     * Lax instance that doesn't validate name and value, and (for methods that accept multiple cookies) keeps
     * cookies in the order in which they were given.
     */
    private static final ClientCookieEncoder LAX = new ClientCookieEncoder(false);

    private static final Cookie[] EMPTY_COOKIES = new Cookie[0];

    /**
     * Returns the strict encoder that validates that name and value chars are in the valid scope and
     * (for methods that accept multiple cookies) sorts cookies into order of decreasing path length,
     * as specified in RFC6265.
     */
    public static ClientCookieEncoder strict() {
        return STRICT;
    }

    /**
     * Returns the lax encoder that doesn't validate name and value, and (for methods that accept multiple
     * cookies) keeps cookies in the order in which they were given.
     */
    public static ClientCookieEncoder lax() {
        return LAX;
    }

    private ClientCookieEncoder(boolean strict) {
        super(strict);
    }

    /**
     * Encodes the specified cookie into a {@code "Cookie"} header value.
     *
     * @param name the cookie name
     * @param value the cookie value
     * @return a Rfc6265 style Cookie header value
     */
    public String encode(String name, String value) {
        return encode(Cookie.builder(name, value).build());
    }

    /**
     * Encodes the specified {@link Cookie} into a single {@code "Cookie"} header value.
     *
     * @param cookie the {@link Cookie} to encode
     * @return a RFC6265-style {@code "Cookie"} header value.
     */
    public String encode(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        final StringBuilder buf = stringBuilder();
        encode(buf, cookie);
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
     * @param cookies the {@link Cookie}s to encode
     * @return a RFC6265-style {@code "Cookie"} header value, or {@code null} if no cookies were specified.
     */
    @Nullable
    public String encode(Cookie... cookies) {
        requireNonNull(cookies, "cookies");
        if (cookies.length == 0) {
            return null;
        }

        final StringBuilder buf = stringBuilder();
        if (isStrict()) {
            if (cookies.length == 1) {
                encode(buf, cookies[0]);
            } else {
                final Cookie[] cookiesSorted = Arrays.copyOf(cookies, cookies.length);
                Arrays.sort(cookiesSorted, COOKIE_COMPARATOR);
                for (Cookie c : cookiesSorted) {
                    encode(buf, c);
                }
            }
        } else {
            for (Cookie c : cookies) {
                encode(buf, c);
            }
        }
        return stripTrailingSeparatorOrNull(buf);
    }

    /**
     * Encodes the specified cookies into a single {@code "Cookie"} header value.
     *
     * @param cookies the {@link Cookie}s to encode
     * @return a RFC6265-style {@code "Cookie"} header value, or {@code null} if no cookies were specified.
     */
    @Nullable
    public String encode(Iterable<? extends Cookie> cookies) {
        final Iterator<? extends Cookie> cookiesIt = checkNotNull(cookies, "cookies").iterator();
        if (!cookiesIt.hasNext()) {
            return null;
        }

        final StringBuilder buf = stringBuilder();
        if (isStrict()) {
            final Cookie firstCookie = cookiesIt.next();
            if (!cookiesIt.hasNext()) {
                encode(buf, firstCookie);
            } else {
                final List<Cookie> cookiesList = InternalThreadLocalMap.get().arrayList();
                cookiesList.add(firstCookie);
                while (cookiesIt.hasNext()) {
                    cookiesList.add(cookiesIt.next());
                }
                final Cookie[] cookiesSorted = cookiesList.toArray(EMPTY_COOKIES);
                Arrays.sort(cookiesSorted, COOKIE_COMPARATOR);
                for (Cookie c : cookiesSorted) {
                    encode(buf, c);
                }
            }
        } else {
            while (cookiesIt.hasNext()) {
                encode(buf, cookiesIt.next());
            }
        }
        return stripTrailingSeparatorOrNull(buf);
    }

    private void encode(StringBuilder buf, Cookie c) {
        final String name = c.name();
        final String value = firstNonNull(c.value(), "");

        validateCookie(name, value);

        if (c.isValueQuoted()) {
            addQuoted(buf, name, value);
        } else {
            add(buf, name, value);
        }
    }
}
