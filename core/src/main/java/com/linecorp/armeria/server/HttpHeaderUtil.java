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

import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Splitter.MapSplitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

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
    static final Function<String, @Nullable String> FORWARDED_CONVERTER =
            value -> TOKEN_SPLITTER.split(value).get("for");

    /**
     * Returns {@link ProxiedAddresses} which were delivered through a proxy server.
     * Returns the specified {@code remoteAddress} if no valid proxy address is found.
     *
     * @param headers the HTTP headers which were received from the client
     * @param clientAddressSources a list of {@link ClientAddressSource}s which are used to determine
     *                             where to look for the client address, in the order of preference
     * @param proxiedAddresses source and destination addresses retrieved from PROXY protocol header
     * @param remoteAddress a remote endpoint of a channel
     * @param filter the filter which evaluates an {@link InetAddress} can be used as a client address
     * @see <a href="https://datatracker.ietf.org/doc/rfc7239/">Forwarded HTTP Extension</a>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-For">X-Forwarded-For</a>
     */
    static ProxiedAddresses determineProxiedAddresses(HttpHeaders headers,
                                                      List<ClientAddressSource> clientAddressSources,
                                                      @Nullable ProxiedAddresses proxiedAddresses,
                                                      InetSocketAddress remoteAddress,
                                                      Predicate<? super InetAddress> filter) {
        for (final ClientAddressSource source : clientAddressSources) {
            if (source.isProxyProtocol()) {
                if (proxiedAddresses != null) {
                    if (filter.test(proxiedAddresses.sourceAddress().getAddress()) &&
                        filter.test(proxiedAddresses.destinationAddresses().get(0).getAddress())) {
                        return proxiedAddresses;
                    }
                }
            } else {
                final ImmutableList.Builder<InetSocketAddress> builder = ImmutableList.builder();
                final AsciiString name = source.header();
                if (name.equals(HttpHeaderNames.FORWARDED)) {
                    headers.getAll(HttpHeaderNames.FORWARDED).forEach(forwarded -> {
                        getAllValidAddress(forwarded, FORWARDED_CONVERTER, filter, builder);
                    });
                } else {
                    headers.getAll(name).forEach(header -> {
                        getAllValidAddress(header, Function.identity(), filter, builder);
                    });
                }

                final ImmutableList<InetSocketAddress> addresses = builder.build();
                if (!addresses.isEmpty()) {
                    return ProxiedAddresses.of(addresses.get(0), addresses.subList(1, addresses.size()));
                }
            }
        }
        // We do not apply the filter to the remote address.
        return ProxiedAddresses.of(remoteAddress);
    }

    @VisibleForTesting
    static void getAllValidAddress(@Nullable String headerValue,
                                   Function<String, @Nullable String> valueConverter,
                                   Predicate<? super InetAddress> filter,
                                   ImmutableList.Builder<InetSocketAddress> accumulator) {
        if (Strings.isNullOrEmpty(headerValue)) {
            return;
        }
        for (String value : CSV_SPLITTER.split(headerValue)) {
            final String v = valueConverter.apply(value);
            if (Strings.isNullOrEmpty(v)) {
                continue;
            }
            try {
                final InetSocketAddress addr = createInetSocketAddress(v);
                if (addr != null && filter.test(addr.getAddress())) {
                    accumulator.add(addr);
                }
            } catch (UnknownHostException e) {
                logger.debug("Failed to create an InetSocketAddress: {}", v, e);
            }
        }
    }

    @Nullable
    private static InetSocketAddress createInetSocketAddress(String address) throws UnknownHostException {
        final char firstChar = address.charAt(0);
        if (firstChar == '_' ||
            (firstChar == 'u' && "unknown".equals(address))) {
            // To early return when the address is not an IP address.
            // - an obfuscated identifier which must start with '_'
            //   - https://datatracker.ietf.org/doc/html/rfc7239#section-6.3
            // - the "unknown" identifier
            return null;
        }

        // Remote quotes. e.g. "[2001:db8:cafe::17]:4711" => [2001:db8:cafe::17]:4711
        final String addr = firstChar == '"' ? QUOTED_STRING_TRIMMER.trimFrom(address) : address;
        try {
            final HostAndPort hostAndPort = HostAndPort.fromString(addr);
            final byte[] addressBytes = NetUtil.createByteArrayFromIpAddressString(hostAndPort.getHost());
            if (addressBytes == null) {
                logger.debug("Failed to parse an address: {}", address);
                return null;
            }
            return new InetSocketAddress(InetAddress.getByAddress(addressBytes),
                                         hostAndPort.getPortOrDefault(0));
        } catch (IllegalArgumentException e) {
            logger.debug("Failed to parse an address: {}", address, e);
            return null;
        }
    }

    static void ensureUniqueMediaTypes(Iterable<MediaType> types, String typeName) {
        requireNonNull(types, typeName);
        final Set<MediaType> set = new HashSet<>();
        for (final MediaType type : types) {
            if (!set.add(type)) {
                throw new IllegalArgumentException(
                        "duplicated media type in " + typeName + ": " + type);
            }
        }
    }

    private HttpHeaderUtil() {}
}
