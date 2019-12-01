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

import java.util.Date;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.ImmutableList;

import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;

/**
 * A <a href="http://tools.ietf.org/html/rfc6265">RFC6265</a> compliant cookie encoder for server side.
 *
 * <p>Note that multiple cookies must be sent as separate "Set-Cookie" headers.</p>
 *
 * <pre>{@code
 * ResponseHeaders headers =
 *     ResponseHeaders.of(HttpStatus.OK,
 *                        HttpHeaderNames.SET_COOKIE,
 *                        ServerCookieEncoder.strict().encode("JSESSIONID", "1234"));
 * }</pre>
 *
 * @see ServerCookieDecoder
 */
public final class ServerCookieEncoder extends CookieEncoder {

    // Forked from netty-4.1.43

    /**
     * Strict encoder that validates that name and value chars are in the valid scope
     * defined in RFC6265, and (for methods that accept multiple cookies) that only
     * one cookie is encoded with any given name. (If multiple cookies have the same
     * name, the last one is the one that is encoded.)
     */
    private static final ServerCookieEncoder STRICT = new ServerCookieEncoder(true);

    /**
     * Lax instance that doesn't validate name and value, and that allows multiple
     * cookies with the same name.
     */
    private static final ServerCookieEncoder LAX = new ServerCookieEncoder(false);

    /**
     * Returns the strict encoder that validates that name and value chars are in the valid scope defined
     * in RFC6265, and (for methods that accept multiple cookies) that only one cookie is encoded with
     * any given name. (If multiple cookies have the same name, the last one is the one that is encoded.)
     */
    public static ServerCookieEncoder strict() {
        return STRICT;
    }

    /**
     * Returns the lax encoder that doesn't validate name and value, and that allows multiple cookies
     * with the same name.
     */
    public static ServerCookieEncoder lax() {
        return LAX;
    }

    private ServerCookieEncoder(boolean strict) {
        super(strict);
    }

    /**
     * Encodes the specified cookie name-value pair into a {@code "Set-Cookie"} header value.
     *
     * @param name the cookie name.
     * @param value the cookie value.
     * @return a single {@code "Set-Cookie"} header value.
     */
    public String encode(String name, String value) {
        return encode(Cookie.of(name, value));
    }

    /**
     * Encodes the specified {@link Cookie} into a {@code "Set-Cookie"} header value.
     *
     * @param cookie the {@link Cookie} to encode.
     * @return a single {@code "Set-Cookie"} header value.
     */
    public String encode(Cookie cookie) {
        final String name = requireNonNull(cookie, "cookie").name();
        final String value = firstNonNull(cookie.value(), "");

        validateCookie(name, value);

        final StringBuilder buf = stringBuilder();

        if (cookie.isValueQuoted()) {
            addQuoted(buf, name, value);
        } else {
            add(buf, name, value);
        }

        if (cookie.maxAge() != Long.MIN_VALUE) {
            add(buf, CookieHeaderNames.MAX_AGE, cookie.maxAge());
            final Date expires = new Date(cookie.maxAge() * 1000 + System.currentTimeMillis());
            buf.append(CookieHeaderNames.EXPIRES);
            buf.append('=');
            DateFormatter.append(expires, buf);
            buf.append(';');
            buf.append(HttpConstants.SP_CHAR);
        }

        final String path = cookie.path();
        if (path != null) {
            add(buf, CookieHeaderNames.PATH, path);
        }

        final String domain = cookie.domain();
        if (domain != null) {
            add(buf, CookieHeaderNames.DOMAIN, domain);
        }
        if (cookie.isSecure()) {
            add(buf, CookieHeaderNames.SECURE);
        }
        if (cookie.isHttpOnly()) {
            add(buf, CookieHeaderNames.HTTPONLY);
        }
        final String sameSite = cookie.sameSite();
        if (sameSite != null) {
            add(buf, "SameSite", sameSite);
        }

        return stripTrailingSeparator(buf);
    }

    /**
     * Encodes the specified {@link Cookie}s into the {@code "Set-Cookie"} header values.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the corresponding {@code "Set-Cookie"} header values.
     */
    public List<String> encode(Cookie... cookies) {
        if (requireNonNull(cookies, "cookies").length == 0) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> encoded = ImmutableList.builderWithExpectedSize(cookies.length);
        for (final Cookie c : cookies) {
            encoded.add(encode(c));
        }
        return encoded.build();
    }

    /**
     * Encodes the specified {@link Cookie}s into the {@code "Set-Cookie"} header values.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the corresponding {@code "Set-Cookie"} header values.
     */
    public List<String> encode(Iterable<? extends Cookie> cookies) {
        final Iterator<? extends Cookie> cookiesIt = requireNonNull(cookies, "cookies").iterator();
        if (!cookiesIt.hasNext()) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> encoded = ImmutableList.builder();
        final Cookie firstCookie = cookiesIt.next();
        encoded.add(encode(firstCookie));
        while (cookiesIt.hasNext()) {
            final Cookie c = cookiesIt.next();
            encoded.add(encode(c));
        }
        return encoded.build();
    }
}
