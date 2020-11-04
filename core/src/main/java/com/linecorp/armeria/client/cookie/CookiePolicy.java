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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.internal.client.PublicSuffix;

import io.netty.util.NetUtil;

/**
 * Specifies how {@link CookieJar} should accept new {@link Cookie}.
 */
@FunctionalInterface
public interface CookiePolicy {

    /**
     * Creates a {@link CookiePolicy} that accepts all cookies.
     */
    static CookiePolicy acceptAll() {
        return (uri, cookie) -> true;
    }

    /**
     * Creates a {@link CookiePolicy} that rejects all cookies.
     */
    static CookiePolicy acceptNone() {
        return (uri, cookie) -> false;
    }

    /**
     * Creates a {@link CookiePolicy} that only accepts cookies from the original server.
     */
    static CookiePolicy acceptOriginalServer() {
        return new CookiePolicy() {
            @Override
            public boolean accept(URI uri, Cookie cookie) {
                requireNonNull(uri, "uri");
                requireNonNull(cookie, "cookie");
                return domainMatch(cookie.domain(), uri.getHost());
            }
        };
    }

    /**
     * Determines whether a {@link Cookie} may be stored for a {@link URI}.
     */
    boolean accept(URI uri, Cookie cookie);

    /**
     * Determines whether a host matches a domain, as specified by RFC 6265
     * <a href="https://tools.ietf.org/html/rfc6265#section-5.1.3">Domain Matching</a>.
     */
    default boolean domainMatch(@Nullable String domain, @Nullable String host) {
        if (domain == null || host == null) {
            return false;
        }
        if (PublicSuffix.get().isPublicSuffix(domain)) {
            return false;
        }
        if (domain.equalsIgnoreCase(host)) {
            return true;
        }
        // ignore the leading dot
        if (!domain.isEmpty() && domain.charAt(0) == '.') {
            domain = domain.substring(1);
        }
        return host.endsWith(domain) && host.charAt(host.length() - domain.length() - 1) == '.' &&
                !NetUtil.isValidIpV4Address(host) && !NetUtil.isValidIpV6Address(host);
    }
}
