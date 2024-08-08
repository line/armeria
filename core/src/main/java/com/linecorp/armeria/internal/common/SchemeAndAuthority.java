/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.util.BitSetUtil.toBitSet;
import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.BitSet;

import com.google.common.base.Strings;
import com.google.common.net.HostAndPort;

import com.linecorp.armeria.common.annotation.Nullable;

public final class SchemeAndAuthority {

    private static final BitSet RESERVED_CHARS = toBitSet("/?#");

    /**
     * Attempts to parse this URI's authority component and return {@link SchemeAndAuthority}.
     * The authority part may have one of the following formats (The userinfo part will be ignored.):
     * <ul>
     *   <li>{@code "unix$3A<socket file>"} for a domain socket authority </li>
     *   <li>{@code "<host>:<port>"} for a host endpoint </li>
     *   <li>{@code "<host>"}, {@code "<host>:"} for a host endpoint with no port number specified</li>
     * </ul>
     * An IPv4 or IPv6 address can be specified in lieu of a host name, e.g. {@code "127.0.0.1:8080"} and
     * {@code "[::1]:8080"}.
     *
     * @throws IllegalArgumentException if {@code scheme} or {@code authority} do not comply with RFC 2396
     */
    public static SchemeAndAuthority of(@Nullable String scheme, String authority) {
        requireNonNull(authority, "authority");

        if (scheme != null) {
            scheme = ArmeriaHttpUtil.schemeValidateAndNormalize(scheme);
        }

        if (authority.startsWith("unix%3A") || authority.startsWith("unix%3a")) {
            return new SchemeAndAuthority(scheme, authority, authority, -1);
        }

        final String authorityWithoutUserInfo = removeUserInfo(authority);

        for (int i = 0; i < authorityWithoutUserInfo.length(); i++) {
            final char c = authorityWithoutUserInfo.charAt(i);
            if (c < 0x80 && RESERVED_CHARS.get(c)) {
                throw new IllegalArgumentException("The authority contains reserved character: " +
                                                   authority + " (" + c + ')');
            }
        }

        try {
            final URI uri = new URI(null, authorityWithoutUserInfo, null, null, null);
            String rawAuthority = uri.getRawAuthority();
            if (Strings.isNullOrEmpty(rawAuthority)) {
                throw new IllegalArgumentException("Invalid authority: " + authority);
            }
            rawAuthority = IDN.toASCII(rawAuthority, IDN.ALLOW_UNASSIGNED);

            final boolean isIpv6 = rawAuthority.charAt(0) == '[';
            if (isIpv6) {
                rawAuthority = removeIpv6ScopeId(rawAuthority);
            }

            final HostAndPort hostAndPort = HostAndPort.fromString(rawAuthority);
            String host = hostAndPort.getHost();
            if (isIpv6) {
                host = '[' + host + ']';
            }
            final int port = hostAndPort.getPortOrDefault(-1);

            return new SchemeAndAuthority(scheme, rawAuthority, host, port);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String removeIpv6ScopeId(String ipV6Authority) {
        final int percentPos = ipV6Authority.indexOf('%');
        if (percentPos == -1) {
            return ipV6Authority;
        }

        final int endBracket = ipV6Authority.indexOf(']');
        // An incomplete IPv6 address is rejected by URI constructor.
        assert endBracket > 0 : "endBracket: " + endBracket + " (expected: > 0)";
        return ipV6Authority.substring(0, percentPos) + ipV6Authority.substring(endBracket);
    }

    private static String removeUserInfo(String authority) {
        final int indexOfDelimiter = authority.lastIndexOf('@');
        if (indexOfDelimiter == -1) {
            return authority;
        }
        return authority.substring(indexOfDelimiter + 1);
    }

    @Nullable
    private final String scheme;
    private final String authority;
    private final String host;
    private final int port;

    private SchemeAndAuthority(@Nullable String scheme, String authority, String host, int port) {
        this.scheme = scheme;
        this.authority = authority;
        this.host = host;
        this.port = port;
    }

    @Nullable
    public String scheme() {
        return scheme;
    }

    public String authority() {
        return authority;
    }

    public String host() {
        return host;
    }

    /**
     * Returns the port number of this authority.
     *
     * @return The port component of this URI,
     *         or {@code -1} if the port is undefined
     */
    public int port() {
        return port;
    }
}
