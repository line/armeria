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

import java.net.CookiePolicy;
import java.net.URI;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;

/**
 * A {@link Cookie} container for a client.
 */
public interface CookieJar {

    /**
     * Returns a {@link Cookies} set of cookies for an {@link URI}.
     */
    Cookies get(URI uri);

    /**
     * Stores cookies for an {@link URI}.
     */
    void set(URI uri, Cookies cookies);

    /**
     * Sets the {@link CookiePolicy} for this {@link CookieJar}.
     * @see CookiePolicy
     */
    void setCookiePolicy(CookiePolicy policy);
}
