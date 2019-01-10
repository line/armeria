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
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

public class HttpHeaderUtilTest {

    private static final Predicate<InetAddress> ACCEPT_ANY = addr -> true;

    @Test
    public void getAddress_Forwarded() throws UnknownHostException {
        // IPv4
        assertThat(forwarded("for=192.0.2.60;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("192.0.2.60"));

        // IPv4 with a port number
        assertThat(forwarded("for=192.0.2.60:4711;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("192.0.2.60"));

        // IPv6
        assertThat(forwarded("for=\"[2001:db8:cafe::17]\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // IPv6 with a port number
        assertThat(forwarded("for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // No "for" parameter in the first value.
        assertThat(forwarded("proto=http," +
                             "for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // The format of the first value is invalid.
        assertThat(forwarded("for=\"[2001:db8:cafe::," +
                             "for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // A obfuscated identifier
        assertThat(forwarded("for=_superhost;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isEqualTo(InetAddress.getByName("192.0.2.61"));

        // The unknown identifier
        assertThat(forwarded("for=unknown;proto=http;by=203.0.113.43,for=192.0.2.61"))
                .isIn(InetAddress.getAllByName("192.0.2.61"));
    }

    @Nullable
    private static InetAddress forwarded(String value) {
        return HttpHeaderUtil.getFirstValidAddress(value, HttpHeaderUtil.FORWARDED_CONVERTER, ACCEPT_ANY);
    }

    @Test
    public void getAddress_X_Forwarded_For() throws UnknownHostException {
        // IPv4
        assertThat(xForwardedFor("192.0.2.60,192.0.2.61,192.0.2.62"))
                .isEqualTo(InetAddress.getByName("192.0.2.60"));

        // IPv4 with a port number
        assertThat(xForwardedFor("192.0.2.60:4711,192.0.2.61:4711,192.0.2.62:4711"))
                .isEqualTo(InetAddress.getByName("192.0.2.60"));

        // IPv6
        assertThat(xForwardedFor("[2001:db8:cafe::17],[2001:db8:cafe::18],[2001:db8:cafe::19]"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // IPv6 with a port number
        assertThat(xForwardedFor("\"[2001:db8:cafe::17]:4711\",[2001:db8:cafe::18]:4711"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // The format of the first value is invalid.
        assertThat(xForwardedFor("[2001:db8:cafe::,[2001:db8:cafe::17]:4711,[2001:db8:cafe::18]:4711"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // The following cases are not a part of X-Forwarded-For specifications, but the first element is
        // definitely not valid.
        // A obfuscated identifier
        assertThat(xForwardedFor("_superhost,[2001:db8:cafe::17]:4711,[2001:db8:cafe::18]:4711"))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // The unknown identifier
        assertThat(xForwardedFor("unknown,[2001:db8:cafe::17]:4711,[2001:db8:cafe::18]:4711"))
                .isIn(InetAddress.getAllByName("2001:db8:cafe::17"));
    }

    @Nullable
    private static InetAddress xForwardedFor(String value) {
        return HttpHeaderUtil.getFirstValidAddress(value, Function.identity(), ACCEPT_ANY);
    }

    @Test
    public void testFilter_Forwarded() throws UnknownHostException {
        // The first address which is not one of site local addresses
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "for=10.0.0.1,for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43",
                HttpHeaderUtil.FORWARDED_CONVERTER, addr -> !addr.isSiteLocalAddress()))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // The first address which is one of site local addresses
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "for=10.0.0.1,for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43",
                HttpHeaderUtil.FORWARDED_CONVERTER, InetAddress::isSiteLocalAddress))
                .isEqualTo(InetAddress.getByName("10.0.0.1"));

        // The first IPv6 address
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "for=10.0.0.1,for=\"[2001:db8:cafe::17]:4711\";proto=http;by=203.0.113.43",
                HttpHeaderUtil.FORWARDED_CONVERTER, addr -> addr instanceof Inet6Address))
                .isEqualTo(InetAddress.getByName("2001:db8:cafe::17"));

        // The first address which is not a loopback
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "for=localhost",
                HttpHeaderUtil.FORWARDED_CONVERTER, addr -> !addr.isLoopbackAddress()))
                .isNull();
    }

    @Test
    public void testFilter_X_Forwarded_For() throws UnknownHostException {
        // The first address which is not one of site local addresses
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "10.0.0.1,8.8.8.8",
                Function.identity(), addr -> !addr.isSiteLocalAddress()))
                .isEqualTo(InetAddress.getByName("8.8.8.8"));

        // The first address which is one of site local addresses
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "8.8.8.8,10.0.0.1",
                Function.identity(), InetAddress::isSiteLocalAddress))
                .isEqualTo(InetAddress.getByName("10.0.0.1"));

        // The first IPv6 address
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "10.0.0.1,[2001:db8:cafe::17]:4711",
                Function.identity(), addr -> addr instanceof Inet6Address))
                .isEqualTo(InetAddress.getByName("[2001:db8:cafe::17]"));

        // The first address which is not a loopback
        assertThat(HttpHeaderUtil.getFirstValidAddress(
                "localhost",
                Function.identity(), addr -> !addr.isLoopbackAddress()))
                .isNull();
    }

    @Test
    public void testClientAddress() throws UnknownHostException {
        final InetAddress remoteAddr = InetAddress.getByName("11.0.0.1");

        // The first address in Forwarded header.
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2",
                               HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1,10.1.0.2"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(InetAddress.getByName("10.0.0.1"));
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(InetAddress.getByName("10.0.0.1"));

        // Get a client address from a custom header.
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2",
                               HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1,10.1.0.2",
                               HttpHeaderNames.of("x-real-ip"), "10.2.0.1,10.2.0.2"),
                ImmutableList.of(ofHeader("x-real-ip"),
                                 ofHeader(HttpHeaderNames.FORWARDED),
                                 ofHeader(HttpHeaderNames.X_FORWARDED_FOR)),
                null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(InetAddress.getByName("10.2.0.1"));

        // The first address in X-Forwarded-For header.
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1,10.1.0.2"),
                ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(InetAddress.getByName("10.1.0.1"));
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(HttpHeaderNames.FORWARDED, "for=10.0.0.1,for=10.0.0.2",
                               HttpHeaderNames.X_FORWARDED_FOR, "10.1.0.1,10.1.0.2"),
                ImmutableList.of(ofHeader(HttpHeaderNames.X_FORWARDED_FOR)),
                null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(InetAddress.getByName("10.1.0.1"));

        // Source address of the proxied addresses.
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(), ClientAddressSource.DEFAULT_SOURCES,
                ProxiedAddresses.of(new InetSocketAddress("10.2.0.1", 50001),
                                    new InetSocketAddress("10.2.0.2", 50002)),
                remoteAddr, ACCEPT_ANY))
                .isEqualTo(InetAddress.getByName("10.2.0.1"));

        // Remote address of the channel.
        assertThat(HttpHeaderUtil.determineClientAddress(
                HttpHeaders.of(), ClientAddressSource.DEFAULT_SOURCES, null, remoteAddr, ACCEPT_ANY))
                .isEqualTo(remoteAddr);
    }
}
