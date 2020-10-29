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
/*
 * Copyright (c) 2005, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
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
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;

/**
 * A default in-memory {@link CookieJar} implementation.
 */
final class DefaultCookieJar implements CookieJar {

    // Forked from OpenJDK
    // https://github.com/openjdk/jdk/blob/790d6e2d2539e0e75c587543e83cf8d73c11e1e7/src/java.base/share/classes/java/net/InMemoryCookieStore.java

    private final Set<Cookie> allCookies;
    /**
     * Used to find cookies of URI that matches the full hostname.
     */
    private final Map<String, Set<Cookie>> hostIndex;
    /**
     * Used to find cookies of URI that matches the cookie domain. For example, if a domain is example.com,
     * foo.example.com will match all cookies in this entry.
     */
    private final Map<String, Set<Cookie>> domainIndex;
    private final ReentrantLock lock;
    private CookiePolicy cookiePolicy;

    DefaultCookieJar() {
        cookiePolicy = CookiePolicy.ACCEPT_ORIGINAL_SERVER;
        allCookies = new HashSet<>();
        hostIndex = new HashMap<>();
        domainIndex = new HashMap<>();
        lock = new ReentrantLock();
    }

    @Override
    public Cookies get(URI uri) {
        requireNonNull(uri, "uri");
        if (allCookies.isEmpty()) {
            return Cookies.of();
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        final String host = uri.getHost();
        final boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        final Set<Cookie> cookies = new HashSet<>();
        lock.lock();
        try {
            getIndex(hostIndex, cookies, path, secure, (h) -> h.equals(host));
            getIndex(domainIndex, cookies, path, secure, (d) -> CookieJar.domainMatches(d, host));
        } finally {
            lock.unlock();
        }
        return Cookies.of(cookies);
    }

    @Override
    public void set(URI uri, Cookies cookies) {
        requireNonNull(uri, "uri");
        requireNonNull(cookies, "cookies");
        final String host = uri.getHost();
        lock.lock();
        try {
            for (Cookie cookie : cookies) {
                // If no path is specified, compute the default path
                // https://tools.ietf.org/html/rfc6265#section-5.1.4
                if (cookie.path() == null) {
                    String path = uri.getPath();
                    if (!path.endsWith("/")) {
                        final int i = path.lastIndexOf('/');
                        if (i > 0) {
                            path = path.substring(0, i + 1);
                        } else {
                            path = "/";
                        }
                    }
                    cookie = cookie.toBuilder().path(path).build();
                }
                // remove existing cookie if present
                allCookies.remove(cookie);
                if (!cookie.isExpired() && cookiePolicy.shouldAccept(uri, cookie)) {
                    allCookies.add(cookie);
                    // a cookie always matches its request's host, so it's always in hostIndex
                    addIndex(hostIndex, host, cookie);
                    if (cookie.domain() != null) {
                        addIndex(domainIndex, requireNonNull(cookie.domain()), cookie);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setCookiePolicy(CookiePolicy cookiePolicy) {
        requireNonNull(cookiePolicy, "cookiePolicy");
        this.cookiePolicy = cookiePolicy;
    }

    private <T> void getIndex(Map<T, Set<Cookie>> index, Set<Cookie> cookies, String path, boolean secure,
                              Predicate<T> condition) {
        for (T key : index.keySet()) {
            if (condition.test(key)) {
                final Set<Cookie> matchedCookies = index.get(key);
                if (matchedCookies != null) {
                    final Iterator<Cookie> it = matchedCookies.iterator();
                    while (it.hasNext()) {
                        final Cookie cookie = it.next();
                        if (allCookies.contains(cookie)) {
                            if (cookie.isExpired()) {
                                it.remove();
                                allCookies.remove(cookie);
                            } else if (pathMatches(path, cookie.path()) && (secure || !cookie.isSecure())) {
                                cookies.add(cookie);
                            }
                        } else {
                            // the cookie has been removed from the main store so remove it from index also
                            it.remove();
                        }
                    }
                }
            }
        }
    }

    private <T> void addIndex(Map<T, Set<Cookie>> index, T key, Cookie cookie) {
        Set<Cookie> cookies = index.get(key);
        if (cookies != null) {
            // remove existing cookie if present
            cookies.remove(cookie);
            cookies.add(cookie);
        } else {
            cookies = new HashSet<>();
            cookies.add(cookie);
            index.put(key, cookies);
        }
    }

    private boolean pathMatches(@Nullable String path, @Nullable String pathToMatch) {
        if (path == null || pathToMatch == null) {
            return false;
        }
        return path.startsWith(pathToMatch);
    }
}
