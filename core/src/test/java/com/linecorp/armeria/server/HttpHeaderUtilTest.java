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

import static com.linecorp.armeria.server.ClientAddressSource.ofHeader;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

class HttpHeaderUtilTest {

    private static final Predicate<InetAddress> ACCEPT_ANY = addr -> true;

    final InetSocketAddress remoteAddr = new InetSocketAddress("11.0.0.1", 0);

    @Test
    void getAddress_Forwarded() throws UnknownHostException {
        // IPv4
        assertThat(forwarded("for=192.0.2.60;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("192.0.2.60", 0),
                                 new InetSocketAddress("192.0.2.61", 0));

        // IPv4 with a port number
        assertThat(forwarded("for=192.0.2.60:4711;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("192.0.2.60", 4711),
                                 new InetSocketAddress("192.0.2.61", 0));

        // IPv6
        assertThat(forwarded("for=\"[2001:db8:cafe::17]\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 0),
                                 new InetSocketAddress("192.0.2.61", 0));

        // IPv6 with a port number
        assertThat(forwarded("for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("192.0.2.61", 0));

        // No "for" parameter in the first value.
        assertThat(forwarded("proto=http," +
                             "for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("192.0.2.61", 0));

        // The format of the first value is invalid.
        assertThat(forwarded("for=\"[2001:db8:cafe::," +
                             "for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("192.0.2.61", 0));

        // A obfuscated identifier
        assertThat(forwarded("for=_superhost;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("192.0.2.61", 0));

        // The unknown identifier
        assertThat(forwarded("for=unknown;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .containsExactly(new InetSocketAddress("192.0.2.61", 0));
    }

    @Nullable
    private static List<InetSocketAddress> forwarded(String value) {
        return getAllValidAddresses(value, HttpHeaderUtil.FORWARDED_CONVERTER, ACCEPT_ANY);
    }

    @Nullable
    private static List<InetSocketAddress> forwarded(String value, Predicate<InetAddress> clientAddressFilter) {
        return getAllValidAddresses(value, HttpHeaderUtil.FORWARDED_CONVERTER, clientAddressFilter);
    }

    @Nullable
    private static List<InetSocketAddress> getAllValidAddresses(
            String value, Function<String, String> valueConverter, Predicate<InetAddress> clientAddressFilter) {
        final Builder<InetSocketAddress> builder = ImmutableList.builder();
        HttpHeaderUtil.getAllValidAddress(value, valueConverter, clientAddressFilter, builder);
        return builder.build();
    }

    @Test
    void getAddress_X_Forwarded_For() throws UnknownHostException {
        // IPv4
        assertThat(xForwardedFor("192.0.2.60,192.0.2.61,192.0.2.62"))
                .containsExactly(new InetSocketAddress("192.0.2.60", 0),
                                 new InetSocketAddress("192.0.2.61", 0),
                                 new InetSocketAddress("192.0.2.62", 0));

        // IPv4 with a port number
        assertThat(xForwardedFor("192.0.2.60:4711,192.0.2.61:4711,192.0.2.62:4711"))
                .containsExactly(new InetSocketAddress("192.0.2.60", 4711),
                                 new InetSocketAddress("192.0.2.61", 4711),
                                 new InetSocketAddress("192.0.2.62", 4711));

        // IPv6
        assertThat(xForwardedFor("[2001:db8:cafe::17],[2001:db8:cafe::18],[2001:db8:cafe::19]"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 0),
                                 new InetSocketAddress("2001:db8:cafe::18", 0),
                                 new InetSocketAddress("2001:db8:cafe::19", 0));

        // IPv6 with a port number
        assertThat(xForwardedFor("\"[2001:db8:cafe::17]:4711\",[2001:db8:cafe::18]:4711"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("2001:db8:cafe::18", 4711));

        // The format of the first value is invalid.
        assertThat(xForwardedFor("[2001:db8:cafe::,[2001:db8:cafe::17]:4711,[2001:db8:cafe::18]:4711"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("2001:db8:cafe::18", 4711));

        // The following cases are not a part of X-Forwarded-For specifications, but the first element is
        // definitely not valid.
        // A obfuscated identifier
        assertThat(xForwardedFor("_superhost,[2001:db8:cafe::17]:4711,[2001:db8:cafe::18]:4711"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("2001:db8:cafe::18", 4711));

        // The unknown identifier
        assertThat(xForwardedFor("unknown,[2001:db8:cafe::17]:4711,[2001:db8:cafe::18]:4711"))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                 new InetSocketAddress("2001:db8:cafe::18", 4711));
    }

    @Nullable
    private static List<InetSocketAddress> xForwardedFor(String value) {
        return getAllValidAddresses(value, Function.identity(), ACCEPT_ANY);
    }

    @Nullable
    private static List<InetSocketAddress> xForwardedFor(String value,
                                                         Predicate<InetAddress> clientAddressFilter) {
        return getAllValidAddresses(value, Function.identity(), clientAddressFilter);
    }

    @Test
    void testFilter_Forwarded() throws UnknownHostException {
        // All addresses which is not one of site local addresses
        assertThat(forwarded("for=10.0.0.1,for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43",
                             addr -> !addr.isSiteLocalAddress()))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711));

        // All addresses which is one of site local addresses
        assertThat(forwarded(
                "for=10.0.0.1,for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43",
                InetAddress::isSiteLocalAddress))
                .containsExactly(new InetSocketAddress("10.0.0.1", 0));

        // All IPv6 addresses
        assertThat(forwarded(
                "for=10.0.0.1,for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43",
                addr -> addr instanceof Inet6Address))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711));

        // All addresses which is not a loopback
        assertThat(forwarded("for=localhost", addr -> !addr.isLoopbackAddress())).isEmpty();
    }

    @Test
    void testFilter_X_Forwarded_For() throws UnknownHostException {
        // All addresses which is not one of site local addresses
        assertThat(xForwardedFor("10.0.0.1,8.8.8.8", addr -> !addr.isSiteLocalAddress()))
                .containsExactly(new InetSocketAddress("8.8.8.8", 0));

        // All addresses which is not one of site local addresses
        assertThat(xForwardedFor("8.8.8.8,10.0.0.1", InetAddress::isSiteLocalAddress))
                .containsExactly(new InetSocketAddress("10.0.0.1", 0));

        // All IPv6 addresses
        assertThat(xForwardedFor("10.0.0.1,[2001:db8:cafe::17]:4711", addr -> addr instanceof Inet6Address))
                .containsExactly(new InetSocketAddress("2001:db8:cafe::17", 4711));

        // The addresses which is not a loopback
        assertThat(xForwardedFor("localhost", addr -> !addr.isLoopbackAddress())).isEmpty();
    }

    @Test
    void proxiedAddresses_Forwarded() throws UnknownHostException {
        // The first address in Forwarded header.
        final ProxiedAddresses proxiedAddresses = ProxiedAddresses.of(
                new InetSocketAddress("10.0.0.1", 0),
                new InetSocketAddress("10.0.0.2", 0));
        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2",
                               HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.2,10.1.0.3"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(proxiedAddresses);

        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(proxiedAddresses);
    }

    @Test
    void proxiedAddresses_IPv6() throws UnknownHostException {
        final InetSocketAddress remoteAddr = new InetSocketAddress("11.0.0.1", 0);
        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(HttpHeaderNames.FORWARDED,
                               "for=\"[2001:db8:cafe::17]:4711\",for=[2001:db8:cafe::18]"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(ProxiedAddresses.of(new InetSocketAddress("2001:db8:cafe::17", 4711),
                                               new InetSocketAddress("2001:db8:cafe::18", 0)));
    }

    @Test
    void proxiedAddresses_custom_header() {
        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2",
                               HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1,10.1.0.2",
                               HttpHeaderNames.of("x-real-ip"), "10.2.0.1,10.2.0.2:443"),
                ImmutableList.of(
                        ofHeader("x-real-ip"),
                        ofHeader(HttpHeaderNames.FORWARDED),
                        ofHeader(HttpHeaderNames.X_FORWARDED_FOR)),
                null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(ProxiedAddresses.of(new InetSocketAddress("10.2.0.1", 0),
                                               new InetSocketAddress("10.2.0.2", 443)));
    }

    @Test
    void proxiedAddresses_X_Forwarded_For() {
        final ProxiedAddresses proxiedAddresses = ProxiedAddresses.of(
                new InetSocketAddress("10.1.0.1", 80),
                new InetSocketAddress("10.1.0.2", 443));
        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1:80,10.1.0.2:443"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(proxiedAddresses);

        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2",
                               HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1:80,10.1.0.2:443"),
                ImmutableList.of(ofHeader(HttpHeaderNames.X_FORWARDED_FOR)),
                null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(proxiedAddresses);
    }

    @Test
    void proxiedAddress_Proxy_Protocol() {
        final ProxiedAddresses proxiedAddresses = ProxiedAddresses.of(
                new InetSocketAddress("10.2.0.1", 50001),
                new InetSocketAddress("10.2.0.2", 50002));
        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(), ClientAddressSource.DEFAULT_SOURCES,
                proxiedAddresses,
                remoteAddr, ACCEPT_ANY))
                .isEqualTo(proxiedAddresses);
    }

    @Test
    void proxiedAddress_no_proxy() {
        assertThat(HttpHeaderUtil.determineProxiedAddresses(
                HttpHeaders.of(), ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(ProxiedAddresses.of(remoteAddr));
    }
}
