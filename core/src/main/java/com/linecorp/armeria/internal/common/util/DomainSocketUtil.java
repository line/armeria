/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.net.UrlEscapers;

import io.netty.util.NetUtil;

public final class DomainSocketUtil {

    // Use an IPv6 address that falls into RFC 6666 (IPv6 Discard Prefix),
    // which will never reach anywhere. See https://datatracker.ietf.org/doc/rfc6666/
    private static final byte[] IPV6_DISCARD_ADDR = {
            1, 0, 0, 0, 0, 0, 0, 0, // 0100::/64
            'A', 'r', 'm', 'e', 'r', 'i', 'a', '!'
    };

    public static final String DOMAIN_SOCKET_IP = NetUtil.bytesToIpAddress(IPV6_DISCARD_ADDR);

    public static final int DOMAIN_SOCKET_PORT = 1;

    private static final Pattern AT_OR_COLON = Pattern.compile("[@:]");

    public static boolean isDomainSocketAddress(InetAddress addr) {
        return Arrays.equals(addr.getAddress(), IPV6_DISCARD_ADDR);
    }

    public static InetAddress toInetAddress(String path) {
        try {
            return InetAddress.getByAddress("unix:" + path, IPV6_DISCARD_ADDR);
        } catch (UnknownHostException e) {
            throw new Error(e);
        }
    }

    public static String toAuthority(String path) {
        requireNonNull(path, "path");
        final String escaped = UrlEscapers.urlPathSegmentEscaper().escape(path);
        // We need to escape `@` and `:` as well, so that the authority contains neither userinfo nor port.
        final Matcher matcher = AT_OR_COLON.matcher(escaped);
        try (TemporaryThreadLocals tmp = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tmp.stringBuilder();
            buf.append("unix%3A");
            for (int i = 0; i < escaped.length();) {
                if (!matcher.find(i)) {
                    buf.append(escaped, i, escaped.length());
                    break;
                }
                final int pos = matcher.start();
                buf.append(escaped, i, pos);
                buf.append(escaped.charAt(pos) == '@' ? "%40" : "%3A");
                i = pos + 1;
            }
            return buf.toString();
        }
    }

    private DomainSocketUtil() {}
}
