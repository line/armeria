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

import com.linecorp.armeria.common.Cookie;

/**
 * Specifies how {@link CookieJar} should accept new {@link Cookie}.
 */
public interface CookiePolicy {

    CookiePolicy ACCEPT_ALL = (uri, cookie) -> true;

    CookiePolicy ACCEPT_NONE = (uri, cookie) -> false;

    CookiePolicy ACCEPT_ORIGINAL_SERVER = (uri, cookie) -> {
        requireNonNull(uri, "uri");
        requireNonNull(cookie, "cookie");
        return CookieJar.domainMatch(cookie.domain(), uri.getHost());
    };

    /**
     * Determines whether a {@link Cookie} may be stored for a {@link URI}.
     */
    boolean shouldAccept(URI uri, Cookie cookie);
}
