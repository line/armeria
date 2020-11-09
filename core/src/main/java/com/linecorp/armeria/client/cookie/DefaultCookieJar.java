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
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;

/**
 * A default in-memory {@link CookieJar} implementation.
 */
final class DefaultCookieJar implements CookieJar {

    private final Set<Cookie> store;
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
        store = new HashSet<>();
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
        final boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        final Set<Cookie> cookies = new HashSet<>();
        lock.lock();
        try {
            filterGet(cookies, host, path, secure);
        } finally {
            lock.unlock();
        }
        return Cookies.of(cookies);
    }

    @Override
    public void set(URI uri, Cookies cookies) {
        requireNonNull(uri, "uri");
        requireNonNull(cookies, "cookies");
        lock.lock();
        try {
            for (Cookie cookie : cookies) {
                cookie = ensureDomainAndPath(cookie, uri);
                // remove similar cookie if present
                store.remove(cookie);
                if (!cookie.isExpired() && cookiePolicy.accept(uri, cookie)) {
                    store.add(cookie);
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

    /**
     * Ensures this cookie has domain and path attributes, otherwise sets them to default values. If domain
     * is absent, the default is the request host, with {@code host-only} flag set to {@code true}. If path is
     * absent, the default is computed from the request path. See RFC 6265
     * <a href="https://tools.ietf.org/html/rfc6265#section-5.3">5.3</a> and
     * <a href="https://tools.ietf.org/html/rfc6265#section-5.1.4">5.1.4</a>
     */
    @VisibleForTesting
    Cookie ensureDomainAndPath(Cookie cookie, URI uri) {
        final CookieBuilder cb = cookie.toBuilder();
        if (Strings.isNullOrEmpty(cookie.domain())) {
            cb.domain(uri.getHost()).hostOnly(true);
        }
        final String cookiePath = cookie.path();
        if (Strings.isNullOrEmpty(cookiePath) || cookiePath.charAt(0) != '/') {
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
            if (cookiePolicy.domainMatches(entry.getKey(), host)) {
                final Iterator<Cookie> it = entry.getValue().iterator();
                while (it.hasNext()) {
                    final Cookie cookie = it.next();
                    if (!store.contains(cookie)) {
                        // the cookie has been removed from the main store so remove it from filter also
                        it.remove();
                        break;
                    }
                    if (cookie.isExpired()) {
                        it.remove();
                        store.remove(cookie);
                        break;
                    }
                    // if a cookie is host-only, host and domain have to be identical
                    final boolean domainMatched = !cookie.isHostOnly() ||
                            host.equalsIgnoreCase(cookie.domain());
                    if (domainMatched && pathMatches(path, cookie.path()) && (secure || !cookie.isSecure())) {
                        cookies.add(cookie);
                    }
                }
            }
        }
    }

    private boolean pathMatches(@Nullable String path, @Nullable String pathToMatch) {
        if (path == null || pathToMatch == null) {
            return false;
        }
        return path.startsWith(pathToMatch);
    }
}
