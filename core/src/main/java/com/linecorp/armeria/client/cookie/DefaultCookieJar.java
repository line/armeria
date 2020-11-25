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

package com.linecorp.armeria.client.cookie;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.NetUtil;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * A default in-memory {@link CookieJar} implementation.
 */
final class DefaultCookieJar implements CookieJar {

    private final Map<Cookie, Long> store;
    /**
     * Used to find cookies for a host that matches a domain. For example, if there is a domain example.com,
     * host example.com or foo.example.com will match all cookies in that entry.
     */
    private final Map<String, Set<Cookie>> filter;
    private final CookiePolicy cookiePolicy;
    private final ReentrantLock lock;

    DefaultCookieJar() {
        this(CookiePolicy.acceptOriginOnly());
    }

    DefaultCookieJar(CookiePolicy cookiePolicy) {
        this.cookiePolicy = cookiePolicy;
        store = new Object2LongOpenHashMap<>();
        filter = new HashMap<>();
        lock = new ReentrantLock();
    }

    @Override
    public Cookies get(URI uri) {
        requireNonNull(uri, "uri");
        if (store.isEmpty()) {
            return Cookies.of();
        }
        final String host = uri.getHost();
        final String path = uri.getPath().isEmpty() ? "/" : uri.getPath();
        final boolean secure = isSecure(uri.getScheme());
        final Set<Cookie> cookies = new HashSet<>();
        lock.lock();
        try {
            filterGet(cookies, host, path, secure);
        } finally {
            lock.unlock();
        }
        return Cookies.of(cookies);
    }

    private boolean isSecure(String scheme) {
        final SessionProtocol parsedProtocol;
        if (scheme.indexOf('+') >= 0) {
            final Scheme parsedScheme = Scheme.tryParse(scheme);
            parsedProtocol = parsedScheme != null ? parsedScheme.sessionProtocol() : null;
        } else {
            parsedProtocol = SessionProtocol.find(scheme);
        }
        return parsedProtocol != null && parsedProtocol.isTls();
    }

    @Override
    public void set(URI uri, Iterable<? extends Cookie> cookies, long createdTimeMillis) {
        requireNonNull(uri, "uri");
        requireNonNull(cookies, "cookies");
        lock.lock();
        try {
            for (Cookie cookie : cookies) {
                cookie = ensureDomainAndPath(cookie, uri);
                // remove similar cookie if present
                store.remove(cookie);
                if (!isExpired(cookie, 0, 0) && cookiePolicy.accept(uri, cookie)) {
                    store.put(cookie, createdTimeMillis);
                    final Set<Cookie> cookieSet = filter.computeIfAbsent(cookie.domain(), s -> new HashSet<>());
                    // remove similar cookie if present
                    cookieSet.remove(cookie);
                    cookieSet.add(cookie);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CookieState state(Cookie cookie, long currentTimeMillis) {
        requireNonNull(cookie, "cookie");
        lock.lock();
        try {
            final Long createdTimeMillis = store.get(cookie);
            if (createdTimeMillis == null) {
                return CookieState.NON_EXISTENT;
            }
            return isExpired(cookie, createdTimeMillis, currentTimeMillis) ?
                   CookieState.EXPIRED : CookieState.EXISTENT;
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpired(Cookie cookie, long createdTimeMillis, long currentTimeMillis) {
        if (cookie.maxAge() == Cookie.UNDEFINED_MAX_AGE) {
            return false;
        }
        if (cookie.maxAge() <= 0) {
            return true;
        }
        final long timePassed = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis - createdTimeMillis);
        return timePassed >= cookie.maxAge();
    }

    /**
     * Ensures this cookie has domain and path attributes, otherwise sets them to default values. If domain
     * is absent, the default is the request host, with {@code host-only} flag set to {@code true}. If path is
     * absent, the default is computed from the request path. See RFC 6265
     * <a href="https://tools.ietf.org/html/rfc6265#section-5.3">5.3</a> and
     * <a href="https://tools.ietf.org/html/rfc6265#section-5.1.4">5.1.4</a>
     */
    @VisibleForTesting
    Cookie ensureDomainAndPath(Cookie cookie, URI uri) {
        final boolean validDomain = !Strings.isNullOrEmpty(cookie.domain());
        final String cookiePath = cookie.path();
        final boolean validPath = !Strings.isNullOrEmpty(cookiePath) && cookiePath.charAt(0) == '/';
        if (validDomain && validPath) {
            return cookie;
        }
        final CookieBuilder cb = cookie.toBuilder();
        if (!validDomain) {
            cb.domain(uri.getHost()).hostOnly(true);
        }
        if (!validPath) {
            String path = uri.getPath();
            if (path.isEmpty()) {
                path = "/";
            } else {
                final int i = path.lastIndexOf('/');
                if (i > 0) {
                    path = path.substring(0, i);
                }
            }
            cb.path(path);
        }
        return cb.build();
    }

    private void filterGet(Set<Cookie> cookies, String host, String path, boolean secure) {
        for (Map.Entry<String, Set<Cookie>> entry : filter.entrySet()) {
            if (domainMatches(entry.getKey(), host)) {
                final Iterator<Cookie> it = entry.getValue().iterator();
                while (it.hasNext()) {
                    final Cookie cookie = it.next();
                    if (!store.containsKey(cookie)) {
                        // the cookie has been removed from the main store so remove it from filter also
                        it.remove();
                        break;
                    }
                    if (state(cookie) == CookieState.EXPIRED) {
                        it.remove();
                        store.remove(cookie);
                        break;
                    }
                    if (cookieMatches(cookie, host, path, secure)) {
                        cookies.add(cookie);
                    }
                }
            }
        }
    }

    private static boolean domainMatches(String domain, String host) {
        if (domain.equalsIgnoreCase(host)) {
            return true;
        }
        return host.endsWith(domain) && host.charAt(host.length() - domain.length() - 1) == '.' &&
               !NetUtil.isValidIpV4Address(host) && !NetUtil.isValidIpV6Address(host);
    }

    private static boolean cookieMatches(Cookie cookie, String host, String path, boolean secure) {
        // if a cookie is host-only, host and domain have to be identical
        final boolean satisfiedHostOnly = !cookie.isHostOnly() || host.equalsIgnoreCase(cookie.domain());
        final boolean satisfiedSecure = secure || !cookie.isSecure();
        final String cookiePath = cookie.path();
        assert cookiePath != null;
        final boolean pathMatched = path.startsWith(cookiePath);
        return satisfiedHostOnly && satisfiedSecure && pathMatched;
    }
}
