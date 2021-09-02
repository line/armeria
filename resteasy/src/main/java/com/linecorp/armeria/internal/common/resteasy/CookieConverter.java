/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.RuntimeDelegate;

import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * A utility to handle conversions between {@link Cookie} and JAX-RS' {javax.ws.rs.core.Cookie} and
 * {@link javax.ws.rs.core.NewCookie}.
 */
@UnstableApi
public final class CookieConverter {

    public static Map<String, javax.ws.rs.core.Cookie> parse(Iterable<String> cookieHeaders, int version) {
        // This has to be a mutable Map! RESTEasy will fail otherwise
        return Streams.stream(cookieHeaders)
                      .flatMap(cookieHeader -> Cookie.fromCookieHeader(cookieHeader).stream())
                      .map(cookie -> new CookieConverter(cookie, version))
                      .collect(toMap(CookieConverter::name, CookieConverter::toJaxrsSetCookie));
    }

    public static Map<String, javax.ws.rs.core.Cookie> parse(Iterable<String> cookieHeaders) {
        return parse(cookieHeaders, javax.ws.rs.core.Cookie.DEFAULT_VERSION);
    }

    private final Cookie cookie;
    private final int version;

    public CookieConverter(Cookie cookie, int version) {
        this.cookie = requireNonNull(cookie, "cookie");
        this.version = version;
    }

    public CookieConverter(Cookie cookie) {
        this(cookie, javax.ws.rs.core.Cookie.DEFAULT_VERSION);
    }

    public CookieConverter(javax.ws.rs.core.NewCookie jaxrsSetCookie) {
        requireNonNull(jaxrsSetCookie, "jaxrsSetCookie");
        final CookieBuilder builder = Cookie.builder(jaxrsSetCookie.getName(), jaxrsSetCookie.getValue());
        final String domain = jaxrsSetCookie.getDomain();
        if (domain != null) {
            builder.domain(domain);
        }
        final String path = jaxrsSetCookie.getPath();
        if (path != null) {
            builder.path(path);
        }
        final int maxAge = jaxrsSetCookie.getMaxAge();
        if (maxAge >= 0) {
            builder.maxAge(maxAge);
        } else {
            @SuppressWarnings("UseOfObsoleteDateTimeApi")
            final Date expiry = jaxrsSetCookie.getExpiry();
            if (expiry != null) {
                builder.maxAge(Duration.between(Instant.now(), expiry.toInstant()).getSeconds());
            }
        }
        if (jaxrsSetCookie.isSecure()) {
            builder.secure(true);
        }
        if (jaxrsSetCookie.isHttpOnly()) {
            builder.httpOnly(true);
        }
        version = jaxrsSetCookie.getVersion();
        cookie = builder.build();
    }

    public CookieConverter(javax.ws.rs.core.Cookie jaxrsCookie) {
        requireNonNull(jaxrsCookie, "jaxrsCookie");
        final CookieBuilder builder =
                Cookie.builder(jaxrsCookie.getName(), jaxrsCookie.getValue());
        final String domain = jaxrsCookie.getDomain();
        if (domain != null) {
            builder.domain(domain);
        }
        final String path = jaxrsCookie.getPath();
        if (path != null) {
            builder.path(path);
        }
        version = jaxrsCookie.getVersion();
        cookie = builder.build();
    }

    public String name() {
        return cookie.name();
    }

    public Cookie cookie() {
        return cookie;
    }

    public int version() {
        return version;
    }

    public AsciiString headerName() {
        return version == 1 ? HttpHeaderNames.SET_COOKIE : HttpHeaderNames.SET_COOKIE2;
    }

    public javax.ws.rs.core.Cookie toJaxrsCookie() {
        return new javax.ws.rs.core.Cookie(cookie.name(), cookie.value(),
                                           cookie.path(), cookie.domain(), version);
    }

    public javax.ws.rs.core.NewCookie toJaxrsSetCookie() {
        return new javax.ws.rs.core.NewCookie(cookie.name(), cookie.value(),
                                              cookie.path(), cookie.domain(), version,
                                              null, (int) cookie.maxAge(), null,
                                              cookie.isSecure(), cookie.isHttpOnly());
    }

    public String toCookieHeader(boolean strict) {
        return cookie.toCookieHeader(strict);
    }

    public String toCookieHeader() {
        return cookie.toCookieHeader();
    }

    public String toSetCookieHeader(boolean strict) {
        return cookie.toSetCookieHeader(strict);
    }

    public String toSetCookieHeader() {
        return cookie.toSetCookieHeader();
    }

    public static RuntimeDelegate.HeaderDelegate<javax.ws.rs.core.Cookie> cookieDelegate() {
        return CookieHeaderDelegate.INSTANCE;
    }

    public static RuntimeDelegate.HeaderDelegate<javax.ws.rs.core.NewCookie> setCookieDelegate() {
        return NewCookieHeaderDelegate.INSTANCE;
    }

    /**
     * See org.jboss.resteasy.plugins.delegates.CookieHeaderDelegate
     */
    private static final class CookieHeaderDelegate
            implements RuntimeDelegate.HeaderDelegate<javax.ws.rs.core.Cookie> {

        private static final CookieHeaderDelegate INSTANCE = new CookieHeaderDelegate();

        @Override
        public javax.ws.rs.core.Cookie fromString(String headerValue) {
            final Cookies cookies = Cookie.fromCookieHeader(headerValue);
            checkArgument(!cookies.isEmpty(), headerValue);
            final CookieConverter converter = new CookieConverter(cookies.iterator().next());
            return converter.toJaxrsCookie();
        }

        @Override
        public String toString(javax.ws.rs.core.Cookie cookie) {
            final CookieConverter converter = new CookieConverter(cookie);
            return converter.toCookieHeader();
        }
    }

    /**
     * See org.jboss.resteasy.plugins.delegates.NewCookieHeaderDelegate
     */
    private static final class NewCookieHeaderDelegate
            implements RuntimeDelegate.HeaderDelegate<NewCookie> {

        private static final NewCookieHeaderDelegate INSTANCE = new NewCookieHeaderDelegate();

        @Override
        public NewCookie fromString(String headerValue) {
            @Nullable
            final Cookie cookie = Cookie.fromSetCookieHeader(headerValue);
            requireNonNull(cookie, headerValue);
            final CookieConverter converter = new CookieConverter(cookie);
            return converter.toJaxrsSetCookie();
        }

        @Override
        public String toString(NewCookie setCookie) {
            final CookieConverter converter = new CookieConverter(setCookie);
            return converter.toSetCookieHeader();
        }
    }
}
