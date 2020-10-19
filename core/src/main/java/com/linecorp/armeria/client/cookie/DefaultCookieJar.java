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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

/**
 * A default in-memory {@link CookieJar} implementation. Uses JDK's {@link CookieManager}.
 * @see CookieManager
 */
final class DefaultCookieJar implements CookieJar {

    private static final Map<String, List<String>> EMPTY_MAP = ImmutableMap.of();
    private final CookieManager cookieManager;

    DefaultCookieJar() {
        cookieManager = new CookieManager();
    }

    @Nullable
    @Override
    public String getCookieHeader(URI uri) {
        try {
            final List<String> cookies = cookieManager.get(uri, EMPTY_MAP).get("Cookie");
            if (cookies == null || cookies.isEmpty()) {
                return null;
            }
            return String.join("; ", cookies);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void set(URI uri, List<String> setCookies) {
        try {
            cookieManager.put(uri, ImmutableMap.of("Set-Cookie", setCookies));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cookiePolicy(CookiePolicy policy) {
        cookieManager.setCookiePolicy(policy);
    }
}
