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

import java.net.URI;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;

/**
 * A {@link Cookie} container for a client.
 */
public interface CookieJar {

    /**
     * Determines whether a host matches a domain, as specified by RFC 6265
     * <a href="https://tools.ietf.org/html/rfc6265#section-5.1.3">Domain Matching</a>.
     */
    static boolean domainMatches(@Nullable String domain, @Nullable String host) {
        // Forked from OpenJDK
        // https://github.com/openjdk/jdk/blob/790d6e2d2539e0e75c587543e83cf8d73c11e1e7/src/java.base/share/classes/java/net/InMemoryCookieStore.java

        if (domain == null || host == null) {
            return false;
        }
        // if there's no embedded dot in domain and domain is not .local
        final boolean isLocalDomain = ".local".equalsIgnoreCase(domain);
        int embeddedDotInDomain = domain.indexOf('.');
        if (embeddedDotInDomain == 0) {
            embeddedDotInDomain = domain.indexOf('.', 1);
        }
        if (!isLocalDomain && (embeddedDotInDomain == -1 || embeddedDotInDomain == domain.length() - 1)) {
            return false;
        }
        // if host contains no dot and domain is .local
        if (host.indexOf('.') == -1 && isLocalDomain) {
            return true;
        }
        final int lengthDiff = host.length() - domain.length();
        if (lengthDiff == 0) {
            // if domain and host are string-compare equal
            return host.equalsIgnoreCase(domain);
        } else if (lengthDiff > 0) {
            // if domain is a suffix of host
            return (domain.equalsIgnoreCase(host.substring(lengthDiff)));
        } else if (lengthDiff == -1) {
            // if domain is actually .host
            return (domain.charAt(0) == '.' && host.equalsIgnoreCase(domain.substring(1)));
        }
        return false;
    }

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
     */
    void setCookiePolicy(CookiePolicy cookiePolicy);
}
