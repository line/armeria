/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Splitter.MapSplitter;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.util.AsciiString;
import io.netty.util.NetUtil;

/**
 * A utility class which provides useful methods for handling HTTP headers.
 */
final class HttpHeaderUtil {
    private static final Logger logger = LoggerFactory.getLogger(HttpHeaderUtil.class);

    private static final Splitter CSV_SPLITTER = Splitter.on(',').omitEmptyStrings();
    private static final MapSplitter TOKEN_SPLITTER =
            Splitter.on(';').omitEmptyStrings().withKeyValueSeparator('=');
    private static final CharMatcher QUOTED_STRING_TRIMMER = CharMatcher.is('"');

    @VisibleForTesting
    static final Function<String, String> FORWARDED_CONVERTER = value -> TOKEN_SPLITTER.split(value).get("for");

    /**
     * Returns an {@link InetAddress} of a client who initiated a request.
     *
     * @param headers the HTTP headers which were received from the client
     * @param clientAddressSources a list of {@link ClientAddressSource}s which are used to determine
     *                             where to look for the client address, in the order of preference
     * @param proxiedAddresses source and destination addresses retrieved from PROXY protocol header
     * @param remoteAddress a remote endpoint of a channel
     * @param filter the filter which evaluates an {@link InetAddress} can be used as a client address
     * @see <a href="https://tools.ietf.org/html/rfc7239">Forwarded HTTP Extension</a>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-For">X-Forwarded-For</a>
     */
    static InetAddress determineClientAddress(HttpHeaders headers,
                                              List<ClientAddressSource> clientAddressSources,
                                              @Nullable ProxiedAddresses proxiedAddresses,
                                              InetAddress remoteAddress,
                                              Predicate<InetAddress> filter) {
        for (final ClientAddressSource source : clientAddressSources) {
            final InetAddress addr;
            if (source.isProxyProtocol()) {
                if (proxiedAddresses != null) {
                    addr = ((InetSocketAddress) proxiedAddresses.sourceAddress()).getAddress();
                    if (filter.test(addr)) {
                        return addr;
                    }
                }
            } else {
                final AsciiString name = source.header();
                if (name.equals(HttpHeaderNames.FORWARDED)) {
                    addr = getFirstValidAddress(
                            headers.get(HttpHeaderNames.FORWARDED), FORWARDED_CONVERTER, filter);
                } else {
                    addr = getFirstValidAddress(headers.get(name), Function.identity(), filter);
                }
                if (addr != null) {
                    return addr;
                }
            }
        }
        // We do not apply the filter to the remote address.
        return remoteAddress;
    }

    @VisibleForTesting
    @Nullable
    static InetAddress getFirstValidAddress(@Nullable String headerValue,
                                            Function<String, String> valueConverter,
                                            Predicate<InetAddress> filter) {
        if (Strings.isNullOrEmpty(headerValue)) {
            return null;
        }
        for (String value : CSV_SPLITTER.split(headerValue)) {
            final String v = valueConverter.apply(value);
            if (Strings.isNullOrEmpty(v)) {
                continue;
            }
            try {
                final InetAddress addr = createInetAddress(v);
                if (addr != null && filter.test(addr)) {
                    return addr;
                }
            } catch (UnknownHostException e) {
                logger.debug("Failed to resolve hostname: {}", v, e);
            }
        }
        return null;
    }

    @Nullable
    private static InetAddress createInetAddress(String address) throws UnknownHostException {
        final char firstChar = address.charAt(0);
        if (firstChar == '_' ||
            (firstChar == 'u' && "unknown".equals(address))) {
            // To early return when the address is not an IP address.
            // - an obfuscated identifier which must start with '_'
            //   - https://tools.ietf.org/html/rfc7239#section-6.3
            // - the "unknown" identifier
            return null;
        }

        // Remote quotes. e.g. "[2001:db8:cafe::17]:4711" => [2001:db8:cafe::17]:4711
        final String addr = firstChar == '"' ? QUOTED_STRING_TRIMMER.trimFrom(address) : address;

        if (NetUtil.isValidIpV4Address(addr) || NetUtil.isValidIpV6Address(addr)) {
            return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(addr));
        }

        if (addr.charAt(0) == '[') {
            final int delim = addr.indexOf(']');
            if (delim > 0) {
                // Remove the port part. e.g. [2001:db8:cafe::17]:4711 => [2001:db8:cafe::17]
                final String withoutPort = addr.substring(0, delim + 1);
                if (NetUtil.isValidIpV6Address(withoutPort)) {
                    return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(withoutPort));
                }
            }
        } else {
            final int delim = addr.indexOf(':');
            if (delim > 0) {
                // Remove the port part. e.g. 192.168.1.1:4711 => 192.168.1.1
                final String withoutPort = addr.substring(0, delim);
                if (NetUtil.isValidIpV4Address(withoutPort)) {
                    return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(withoutPort));
                }
            }
        }
        // Skip if it is an invalid address.
        logger.debug("Failed to parse an address: {}", address);
        return null;
    }

    private HttpHeaderUtil() {}
}
