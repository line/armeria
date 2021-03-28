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
import com.linecorp.armeria.internal.client.PublicSuffix;

import io.netty.util.NetUtil;

/**
 * A {@link CookiePolicy} implementation that accepts only cookies sent from the original server.
 */
final class AcceptOriginCookiePolicy implements CookiePolicy {

    private static final AcceptOriginCookiePolicy INSTANCE = new AcceptOriginCookiePolicy();

    static AcceptOriginCookiePolicy get() {
        return INSTANCE;
    }

    private AcceptOriginCookiePolicy() {}

    /**
     * Accepts a cookie if its domain is non-null, not a public suffix, and matches the server host as
     * specified by RFC 6265 <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.1.3">Domain Matching</a>.
     */
    @Override
    public boolean accept(URI uri, Cookie cookie) {
        requireNonNull(uri, "uri");
        requireNonNull(cookie, "cookie");
        final String domain = cookie.domain();
        final String host = uri.getHost();
        if (domain == null || host == null) {
            return false;
        }
        if (PublicSuffix.get().isPublicSuffix(domain)) {
            return false;
        }
        if (domain.equalsIgnoreCase(host)) {
            return true;
        }
        return host.endsWith(domain) && host.charAt(host.length() - domain.length() - 1) == '.' &&
               !NetUtil.isValidIpV4Address(host) && !NetUtil.isValidIpV6Address(host);
    }
}
