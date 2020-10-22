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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;

/**
 * A default in-memory {@link CookieJar} implementation that delegates to JDK's {@link CookieManager}.
 * @see CookieManager
 */
final class DefaultCookieJar implements CookieJar {

    private static final Map<String, List<String>> EMPTY_MAP = ImmutableMap.of();
    private final CookieManager cookieManager;

    DefaultCookieJar() {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    }

    @Override
    public Cookies get(URI uri) {
        try {
            final List<String> cookieList = cookieManager.get(uri, EMPTY_MAP).get("Cookie");
            if (cookieList == null || cookieList.isEmpty()) {
                return Cookies.of();
            }
            final List<Cookie> cookies = new ArrayList<>();
            cookieList.forEach(c -> cookies.addAll(Cookie.fromCookieHeader(c)));
            return Cookies.of(cookies);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void set(URI uri, Cookies cookies) {
        try {
            cookieManager.put(uri, ImmutableMap.of("Set-Cookie", Cookie.toSetCookieHeaders(cookies)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void setCookiePolicy(CookiePolicy policy) {
        cookieManager.setCookiePolicy(policy);
    }
}
