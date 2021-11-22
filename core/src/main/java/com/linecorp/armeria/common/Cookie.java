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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An interface defining an
 * <a href="http://en.wikipedia.org/wiki/HTTP_cookie">HTTP cookie</a>.
 */
@SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
public interface Cookie extends Comparable<Cookie> {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/f89dfb0bd53af45eb5f1c1dcc7d9badd889d17f0/codec-http/src/main/java/io/netty/handler/codec/http/Cookie.java

    /**
     * Returns a newly created {@link Cookie}.
     *
     * @param name the name of the {@link Cookie}
     * @param value the value of the {@link Cookie}
     */
    static Cookie of(String name, String value) {
        return builder(name, value).build();
    }

    /**
     * Returns a newly created {@link CookieBuilder} which builds a {@link Cookie}.
     *
     * @param name the name of the {@link Cookie}
     * @param value the value of the {@link Cookie}
     *
     * @deprecated Use {@link #secureBuilder(String, String)} instead to create secure cookie.
     */
    @Deprecated
    static CookieBuilder builder(String name, String value) {
        return new CookieBuilder(name, value);
    }

    /**
     * Returns a newly created {@link CookieBuilder} which builds a {@link Cookie} with secure settings.
     *
     * @param name the name of the {@link Cookie}
     * @param value the value of the {@link Cookie}
     */
    static CookieBuilder secureBuilder(String name, String value) {
        return new CookieBuilder(name, value).secure(true).httpOnly(true);
    }

    /**
     * Decodes the specified {@code "Cookie"} header value into a set of {@link Cookie}s.
     *
     * @param cookieHeader the {@code "Cookie"} header value.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromCookieHeader(String cookieHeader) {
        return fromCookieHeader(true, cookieHeader);
    }

    /**
     * Decodes the specified {@code "Cookie"} header value into a set of {@link Cookie}s.
     *
     * @param strict whether to validate that the cookie names and values are in the valid scope
     *               defined in RFC 6265.
     * @param cookieHeader the {@code "Cookie"} header value.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromCookieHeader(boolean strict, String cookieHeader) {
        requireNonNull(cookieHeader, "cookieHeader");
        if (cookieHeader.isEmpty()) {
            return Cookies.of();
        }
        return ServerCookieDecoder.decode(strict, cookieHeader);
    }

    /**
     * Decodes the specified {@code "Cookie"} header values into a set of {@link Cookie}s.
     *
     * @param cookieHeaders the {@code "Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromCookieHeaders(String... cookieHeaders) {
        return fromCookieHeaders(true, cookieHeaders);
    }

    /**
     * Decodes the specified {@code "Cookie"} header values into a set of {@link Cookie}s.
     *
     * @param cookieHeaders the {@code "Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromCookieHeaders(Iterable<String> cookieHeaders) {
        return fromCookieHeaders(true, cookieHeaders);
    }

    /**
     * Decodes the specified {@code "Cookie"} header values into a set of {@link Cookie}s.
     *
     * @param strict whether to validate that the cookie names and values are in the valid scope
     *               defined in RFC 6265.
     * @param cookieHeaders the {@code "Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromCookieHeaders(boolean strict, String... cookieHeaders) {
        requireNonNull(cookieHeaders, "cookieHeaders");
        return fromCookieHeaders(strict, ImmutableList.copyOf(cookieHeaders));
    }

    /**
     * Decodes the specified {@code "Cookie"} header values into a set of {@link Cookie}s.
     *
     * @param strict whether to validate that the cookie names and values are in the valid scope
     *               defined in RFC 6265.
     * @param cookieHeaders the {@code "Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromCookieHeaders(boolean strict, Iterable<String> cookieHeaders) {
        requireNonNull(cookieHeaders, "cookieHeaders");
        final Iterator<String> it = cookieHeaders.iterator();
        if (!it.hasNext()) {
            return Cookies.of();
        }

        final ImmutableSet.Builder<Cookie> builder = ImmutableSet.builder();
        do {
            final String v = it.next();
            requireNonNull(v, "cookieHeaders contains null.");
            final Cookies cookies = fromCookieHeader(strict, v);
            builder.addAll(cookies);
        } while (it.hasNext());

        return Cookies.of(builder.build());
    }

    /**
     * Encodes the specified {@link Cookie}s into a {@code "Cookie"} header value.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Cookie"} header value.
     */
    static String toCookieHeader(Cookie... cookies) {
        return toCookieHeader(true, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into a {@code "Cookie"} header value.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Cookie"} header value.
     *
     * @throws IllegalArgumentException if {@code cookies} is empty.
     */
    static String toCookieHeader(Iterable<? extends Cookie> cookies) {
        return toCookieHeader(true, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into a {@code "Cookie"} header value.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Cookie"} header value.
     *
     * @throws IllegalArgumentException if {@code cookies} is empty.
     */
    static String toCookieHeader(Collection<? extends Cookie> cookies) {
        return toCookieHeader(true, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into a {@code "Cookie"} header value.
     *
     * @param strict whether to validate that cookie names and values are in the valid scope
     *               defined in RFC 6265 and to sort the {@link Cookie}s into order of decreasing path length,
     *               as specified in RFC 6265. If {@code false}, the {@link Cookie}s are encoded in the order
     *               in which they are given.
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Cookie"} header value.
     */
    static String toCookieHeader(boolean strict, Cookie... cookies) {
        requireNonNull(cookies, "cookies");
        checkArgument(cookies.length != 0, "cookies is empty.");
        return ClientCookieEncoder.encode(strict, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into a {@code "Cookie"} header value.
     *
     * @param strict whether to validate that cookie names and values are in the valid scope
     *               defined in RFC 6265 and to sort the {@link Cookie}s into order of decreasing path length,
     *               as specified in RFC 6265. If {@code false}, the {@link Cookie}s are encoded in the order
     *               in which they are given.
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Cookie"} header value.
     *
     * @throws IllegalArgumentException if {@code cookies} is empty.
     */
    static String toCookieHeader(boolean strict, Iterable<? extends Cookie> cookies) {
        if (cookies instanceof Collection) {
            @SuppressWarnings("unchecked")
            final Collection<? extends Cookie> cast = (Collection<? extends Cookie>) cookies;
            return toCookieHeader(strict, cast);
        }

        requireNonNull(cookies, "cookies");
        final Iterator<? extends Cookie> it = cookies.iterator();
        checkArgument(it.hasNext(), "cookies is empty.");
        return ClientCookieEncoder.encode(strict, it);
    }

    /**
     * Encodes the specified {@link Cookie}s into a {@code "Cookie"} header value.
     *
     * @param strict whether to validate that cookie names and values are in the valid scope
     *               defined in RFC 6265 and to sort the {@link Cookie}s into order of decreasing path length,
     *               as specified in RFC 6265. If {@code false}, the {@link Cookie}s are encoded in the order
     *               in which they are given.
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Cookie"} header value.
     *
     * @throws IllegalArgumentException if {@code cookies} is empty.
     */
    static String toCookieHeader(boolean strict, Collection<? extends Cookie> cookies) {
        requireNonNull(cookies, "cookies");
        checkArgument(!cookies.isEmpty(), "cookies is empty.");
        return ClientCookieEncoder.encode(strict, cookies.iterator());
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header value into a {@link Cookie}.
     *
     * @param setCookieHeader the {@code "Set-Cookie"} header value.
     * @return the decoded {@link Cookie} if decoded successfully, or {@code null} otherwise.
     */
    @Nullable
    static Cookie fromSetCookieHeader(String setCookieHeader) {
        return fromSetCookieHeader(true, setCookieHeader);
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header value into a {@link Cookie}.
     *
     * @param strict whether to validate the cookie names and values are in the valid scope defined in RFC 6265.
     * @param setCookieHeader the {@code "Set-Cookie"} header value.
     * @return the decoded {@link Cookie} if decoded successfully, or {@code null} otherwise.
     */
    @Nullable
    static Cookie fromSetCookieHeader(boolean strict, String setCookieHeader) {
        requireNonNull(setCookieHeader, "setCookieHeader");
        if (setCookieHeader.isEmpty()) {
            return null;
        }
        return ClientCookieDecoder.decode(strict, setCookieHeader);
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header values into {@link Cookie}s.
     *
     * @param setCookieHeaders the {@code "Set-Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromSetCookieHeaders(String... setCookieHeaders) {
        return fromSetCookieHeaders(true, setCookieHeaders);
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header values into {@link Cookie}s.
     *
     * @param setCookieHeaders the {@code "Set-Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromSetCookieHeaders(Iterable<String> setCookieHeaders) {
        return fromSetCookieHeaders(true, setCookieHeaders);
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header values into {@link Cookie}s.
     *
     * @param setCookieHeaders the {@code "Set-Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromSetCookieHeaders(Collection<String> setCookieHeaders) {
        return fromSetCookieHeaders(true, setCookieHeaders);
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header values into {@link Cookie}s.
     *
     * @param strict whether to validate the cookie names and values are in the valid scope defined in RFC 6265.
     * @param setCookieHeaders the {@code "Set-Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromSetCookieHeaders(boolean strict, String... setCookieHeaders) {
        requireNonNull(setCookieHeaders, "setCookieHeaders");
        if (setCookieHeaders.length == 0) {
            return Cookies.of();
        }

        final ImmutableSet.Builder<Cookie> builder =
                ImmutableSet.builderWithExpectedSize(setCookieHeaders.length);
        for (String v : setCookieHeaders) {
            requireNonNull(v, "setCookieHeaders contains null.");
            final Cookie cookie = fromSetCookieHeader(strict, v);
            if (cookie != null) {
                builder.add(cookie);
            }
        }

        return Cookies.of(builder.build());
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header values into {@link Cookie}s.
     *
     * @param strict whether to validate the cookie names and values are in the valid scope defined in RFC 6265.
     * @param setCookieHeaders the {@code "Set-Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromSetCookieHeaders(boolean strict, Iterable<String> setCookieHeaders) {
        if (setCookieHeaders instanceof Collection) {
            return fromSetCookieHeaders(strict, (Collection<String>) setCookieHeaders);
        }

        requireNonNull(setCookieHeaders, "setCookieHeaders");
        final Iterator<String> it = setCookieHeaders.iterator();
        if (!it.hasNext()) {
            return Cookies.of();
        }

        return CookieUtil.fromSetCookieHeaders(ImmutableSet.builder(), strict, it);
    }

    /**
     * Decodes the specified {@code "Set-Cookie"} header values into {@link Cookie}s.
     *
     * @param strict whether to validate the cookie names and values are in the valid scope defined in RFC 6265.
     * @param setCookieHeaders the {@code "Set-Cookie"} header values.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies fromSetCookieHeaders(boolean strict, Collection<String> setCookieHeaders) {
        requireNonNull(setCookieHeaders, "setCookieHeaders");
        if (setCookieHeaders.isEmpty()) {
            return Cookies.of();
        }

        return CookieUtil.fromSetCookieHeaders(ImmutableSet.builderWithExpectedSize(setCookieHeaders.size()),
                                               strict, setCookieHeaders.iterator());
    }

    /**
     * Encodes the specified {@link Cookie}s into {@code "Set-Cookie"} header values.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Set-Cookie"} header values.
     */
    static List<String> toSetCookieHeaders(Cookie... cookies) {
        return toSetCookieHeaders(true, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into {@code "Set-Cookie"} header values.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Set-Cookie"} header values.
     */
    static List<String> toSetCookieHeaders(Iterable<? extends Cookie> cookies) {
        return toSetCookieHeaders(true, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into {@code "Set-Cookie"} header values.
     *
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Set-Cookie"} header values.
     */
    static List<String> toSetCookieHeaders(Collection<? extends Cookie> cookies) {
        return toSetCookieHeaders(true, cookies);
    }

    /**
     * Encodes the specified {@link Cookie}s into {@code "Set-Cookie"} header values.
     *
     * @param strict whether to validate that the cookie names and values are in the valid scope
     *               defined in RFC 6265.
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Set-Cookie"} header values.
     */
    static List<String> toSetCookieHeaders(boolean strict, Cookie... cookies) {
        requireNonNull(cookies, "cookies");
        if (cookies.length == 0) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> encoded = ImmutableList.builderWithExpectedSize(cookies.length);
        for (final Cookie c : cookies) {
            encoded.add(c.toSetCookieHeader(strict));
        }
        return encoded.build();
    }

    /**
     * Encodes the specified {@link Cookie}s into {@code "Set-Cookie"} header values.
     *
     * @param strict whether to validate that the cookie names and values are in the valid scope
     *               defined in RFC 6265.
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Set-Cookie"} header values.
     */
    static List<String> toSetCookieHeaders(boolean strict, Iterable<? extends Cookie> cookies) {
        if (cookies instanceof Collection) {
            @SuppressWarnings("unchecked")
            final Collection<? extends Cookie> cast = (Collection<? extends Cookie>) cookies;
            return toSetCookieHeaders(strict, cast);
        }

        requireNonNull(cookies, "cookies");
        final Iterator<? extends Cookie> it = cookies.iterator();
        if (!it.hasNext()) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> encoded = ImmutableList.builder();
        return CookieUtil.toSetCookieHeaders(encoded, strict, it);
    }

    /**
     * Encodes the specified {@link Cookie}s into {@code "Set-Cookie"} header values.
     *
     * @param strict whether to validate that the cookie names and values are in the valid scope
     *               defined in RFC 6265.
     * @param cookies the {@link Cookie}s to encode.
     * @return the encoded {@code "Set-Cookie"} header values.
     */
    static List<String> toSetCookieHeaders(boolean strict, Collection<? extends Cookie> cookies) {
        requireNonNull(cookies, "cookies");
        if (cookies.isEmpty()) {
            return ImmutableList.of();
        }

        return CookieUtil.toSetCookieHeaders(ImmutableList.builderWithExpectedSize(cookies.size()),
                                             strict, cookies.iterator());
    }

    /**
     * Constant for undefined MaxAge attribute value.
     */
    long UNDEFINED_MAX_AGE = Long.MIN_VALUE;

    /**
     * Returns the name of this {@link Cookie}.
     */
    String name();

    /**
     * Returns the value of this {@link Cookie}.
     */
    String value();

    /**
     * Returns whether the raw value of this {@link Cookie} was wrapped with double quotes
     * in the original {@code "Set-Cookie"} header.
     */
    boolean isValueQuoted();

    /**
     * Returns the domain of this {@link Cookie}.
     *
     * @return the domain, or {@code null}.
     */
    @Nullable
    String domain();

    /**
     * Returns the path of this {@link Cookie}.
     *
     * @return the path, or {@code null}.
     */
    @Nullable
    String path();

    /**
     * Returns the maximum age of this {@link Cookie} in seconds.
     *
     * @return the maximum age, or {@link Cookie#UNDEFINED_MAX_AGE} if unspecified.
     */
    long maxAge();

    /**
     * Returns whether this {@link Cookie} is secure.
     */
    boolean isSecure();

    /**
     * Returns whether this {@link Cookie} can only be accessed via HTTP.
     * If this returns {@code true}, the {@link Cookie} cannot be accessed through client side script.
     * However, it works only if the browser supports it.
     * Read <a href="http://www.owasp.org/index.php/HTTPOnly">here</a> for more information.
     */
    boolean isHttpOnly();

    /**
     * Returns the <a href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-07#section-4.1.2.7"
     * >{@code "SameSite"}</a> attribute of this {@link Cookie}.
     *
     * @return the {@code "SameSite"} attribute, or {@code null}.
     */
    @Nullable
    String sameSite();

    /**
     * Returns whether this {@link Cookie} should only match its original host in domain matching. If this
     * returns {@code true}, should {@link #domain()} returns a non-null value, it's going to be the original
     * request host.
     */
    boolean isHostOnly();

    /**
     * Encodes this {@link Cookie} into a single {@code "Cookie"} header value.
     * Note that you must use {@link #toCookieHeader(Collection)} when encoding more than one {@link Cookie},
     * because it is prohibited to send multiple {@code "Cookie"} headers in an HTTP request,
     * according to <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.4">RFC 6265</a>.
     *
     * @return a single RFC 6265-style {@code "Cookie"} header value.
     */
    default String toCookieHeader() {
        return toCookieHeader(true);
    }

    /**
     * Encodes this {@link Cookie} into a single {@code "Cookie"} header value.
     * Note that you must use {@link #toCookieHeader(boolean, Collection)} when encoding
     * more than one {@link Cookie}, because it is prohibited to send multiple {@code "Cookie"} headers
     * in an HTTP request, according to <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.4">RFC 6265</a>.
     *
     * @param strict whether to validate that the cookie name and value are in the valid scope
     *               defined in RFC 6265.
     * @return a single RFC 6265-style {@code "Cookie"} header value.
     */
    default String toCookieHeader(boolean strict) {
        return ClientCookieEncoder.encode(strict, this);
    }

    /**
     * Encodes this {@link Cookie} into a single {@code "Set-Cookie"} header value.
     *
     * @return a single {@code "Set-Cookie"} header value.
     */
    default String toSetCookieHeader() {
        return toSetCookieHeader(true);
    }

    /**
     * Encodes this {@link Cookie} into a single {@code "Set-Cookie"} header value.
     *
     * @param strict whether to validate that the cookie name and value are in the valid scope
     *               defined in RFC 6265.
     * @return a single {@code "Set-Cookie"} header value.
     */
    default String toSetCookieHeader(boolean strict) {
        return ServerCookieEncoder.encode(strict, this);
    }

    /**
     * Returns a new {@link CookieBuilder} created from this {@link Cookie}.
     *
     * @see #withMutations(Consumer)
     */
    default CookieBuilder toBuilder() {
        return new CookieBuilder(this);
    }

    /**
     * Returns a new {@link Cookie} which is the result from the mutation by the specified {@link Consumer}.
     * This method is a shortcut for:
     * <pre>{@code
     * builder = toBuilder();
     * mutator.accept(builder);
     * return builder.build();
     * }</pre>
     *
     * @see #toBuilder()
     */
    default Cookie withMutations(Consumer<CookieBuilder> mutator) {
        final CookieBuilder builder = toBuilder();
        mutator.accept(builder);
        return builder.build();
    }

    @Override
    default int compareTo(Cookie c) {
        int v = name().compareTo(c.name());
        if (v != 0) {
            return v;
        }

        v = value().compareTo(c.value());
        if (v != 0) {
            return v;
        }

        final String path = path();
        final String otherPath = c.path();
        if (path == null) {
            if (otherPath != null) {
                return -1;
            }
        } else if (otherPath == null) {
            return 1;
        } else {
            v = path.compareTo(otherPath);
            if (v != 0) {
                return v;
            }
        }

        final String domain = domain();
        final String otherDomain = c.domain();
        if (domain == null) {
            if (otherDomain != null) {
                return -1;
            }
        } else if (otherDomain == null) {
            return 1;
        } else {
            v = domain.compareToIgnoreCase(otherDomain);
            return v;
        }

        return 0;
    }
}
