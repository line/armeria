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

import java.net.URI;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;

/**
 * A {@link Cookie} container for a client.
 */
public interface CookieJar {

    /**
     * Returns the unexpired {@link Cookies} for the specified {@link URI}.
     */
    Cookies get(URI uri);

    /**
     * Stores the specified {@link Cookies} for the {@link URI}.
     */
    void set(URI uri, Iterable<? extends Cookie> cookies);

    /**
     * Stores the specified {@link Cookies} for the {@link URI} given the creation time.
     */
    void set(URI uri, Iterable<? extends Cookie> cookies, long createdTimeMillis);

    /**
     * Determines if a cookie is expired. Throws an {@link IllegalArgumentException} if the cookie is not
     * found in the jar.
     */
    boolean isExpired(Cookie cookie);

    /**
     * Determines if a cookie is expired given the current time. Throws an {@link IllegalArgumentException}
     * if the cookie is not found in the jar.
     */
    boolean isExpired(Cookie cookie, long currentTimeMillis);
}
