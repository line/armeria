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
import static com.linecorp.armeria.common.CookieUtil.validateCookie;
import static java.util.Objects.requireNonNull;

import java.util.Date;

import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;

/**
 * A <a href="https://datatracker.ietf.org/doc/rfc6265/">RFC 6265</a> compliant cookie encoder for server side.
 *
 * <p>Note that multiple cookies must be sent as separate "Set-Cookie" headers.</p>
 *
 * @see ServerCookieDecoder
 */
final class ServerCookieEncoder {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/5d448377e94ca1eca3ec994d34a1170912e57ae9/codec-http/src/main/java/io/netty/handler/codec/http/cookie/ServerCookieEncoder.java

    /**
     * Encodes the specified {@link Cookie} into a {@code "Set-Cookie"} header value.
     *
     * @param strict whether to validate that name and value chars are in the valid scope defined in RFC 6265.
     * @param cookie the {@link Cookie} to encode.
     * @return a single {@code "Set-Cookie"} header value.
     */
    static String encode(boolean strict, Cookie cookie) {
        final String name = requireNonNull(cookie, "cookie").name();
        final String value = firstNonNull(cookie.value(), "");

        validateCookie(strict, name, value);

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

    private ServerCookieEncoder() {}
}
