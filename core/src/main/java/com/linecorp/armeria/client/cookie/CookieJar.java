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
     * The possible states of a cookie in the jar.
     */
    enum CookieState {
        EXISTENT,
        EXPIRED,
        NON_EXISTENT
    }

    /**
     * Returns the unexpired {@link Cookies} for the specified {@link URI}.
     */
    Cookies get(URI uri);

    /**
     * Stores the specified {@link Cookies} for the {@link URI}. This method is a shortcut for
     * {@code set(uri, cookies, System.currentTimeMillis())}.
     */
    default void set(URI uri, Iterable<? extends Cookie> cookies) {
        set(uri, cookies, System.currentTimeMillis());
    }

    /**
     * Stores the specified {@link Cookies} for the {@link URI} given the creation time.
     */
    void set(URI uri, Iterable<? extends Cookie> cookies, long createdTimeMillis);

    /**
     * Determines the state of a cookie. This method is a shortcut for
     * {@code state(cookie, System.currentTimeMillis())}.
     */
    default CookieState state(Cookie cookie) {
        return state(cookie, System.currentTimeMillis());
    }

    /**
     * Determines the state of a cookie given the current time.
     */
    CookieState state(Cookie cookie, long currentTimeMillis);
}
