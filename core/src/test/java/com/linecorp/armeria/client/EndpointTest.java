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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.testing.EqualsTester;

import com.linecorp.armeria.client.Endpoint.Type;
import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.common.AttributesBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.internal.common.util.DomainSocketUtil;

import io.netty.util.AttributeKey;

class EndpointTest {

    @Test
    void parse() {
        final Endpoint foo = Endpoint.parse("foo");
        assertThat(foo).isEqualTo(Endpoint.of("foo"));
        assertThatThrownBy(foo::port).isInstanceOf(IllegalStateException.class);
        assertThat(foo.type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThat(foo.weight()).isEqualTo(1000);
        assertThat(foo.ipAddr()).isNull();
        assertThat(foo.ipFamily()).isNull();
        assertThat(foo.hasIpAddr()).isFalse();
        assertThat(foo.hasPort()).isFalse();
        assertThat(foo.toUri("none+http").toString()).isEqualTo("none+http://foo");

        final Endpoint bar = Endpoint.parse("bar:80");
        assertThat(bar).isEqualTo(Endpoint.of("bar", 80));
        assertThat(bar.type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThat(bar.port()).isEqualTo(80);
        assertThat(bar.weight()).isEqualTo(1000);
        assertThat(bar.ipAddr()).isNull();
        assertThat(bar.ipFamily()).isNull();
        assertThat(bar.hasIpAddr()).isFalse();
        assertThat(bar.hasPort()).isTrue();
        assertThat(bar.toUri("none+http").toString()).isEqualTo("none+http://bar:80");

        final Endpoint barWithUserInfo = Endpoint.parse("foo@bar:80");
        assertThat(barWithUserInfo).isEqualTo(Endpoint.of("bar", 80));
        assertThat(barWithUserInfo.type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThat(barWithUserInfo.port()).isEqualTo(80);
        assertThat(barWithUserInfo.weight()).isEqualTo(1000);
        assertThat(barWithUserInfo.ipAddr()).isNull();
        assertThat(barWithUserInfo.ipFamily()).isNull();
        assertThat(barWithUserInfo.hasIpAddr()).isFalse();
        assertThat(barWithUserInfo.hasPort()).isTrue();
        assertThat(barWithUserInfo.toUri("none+http").toString()).isEqualTo("none+http://bar:80");

        final Endpoint barWithoutPort = Endpoint.parse("foo@bar:");
        assertThat(barWithoutPort).isEqualTo(Endpoint.of("bar"));
        assertThat(barWithoutPort.type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThatThrownBy(barWithoutPort::port).isInstanceOf(IllegalStateException.class);
        assertThat(barWithoutPort.weight()).isEqualTo(1000);
        assertThat(barWithoutPort.ipAddr()).isNull();
        assertThat(barWithoutPort.ipFamily()).isNull();
        assertThat(barWithoutPort.hasIpAddr()).isFalse();
        assertThat(barWithoutPort.hasPort()).isFalse();
        assertThat(barWithoutPort.toUri("none+http").toString()).isEqualTo("none+http://bar");
    }

    @Test
    void hostWithoutPort() {
        final Endpoint foo = Endpoint.of("foo.com");
        assertThat(foo.type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThat(foo.host()).isEqualTo("foo.com");
        assertThat(foo.ipAddr()).isNull();
        assertThat(foo.ipFamily()).isNull();
        assertThat(foo.hasIpAddr()).isFalse();
        assertThat(foo.port(42)).isEqualTo(42);
        assertThat(foo.hasPort()).isFalse();
        assertThat(foo.withDefaultPort(42).port()).isEqualTo(42);
        assertThat(foo.weight()).isEqualTo(1000);
        assertThat(foo.authority()).isEqualTo("foo.com");
        assertThat(foo.withIpAddr(null)).isSameAs(foo);
        assertThat(foo.toUri("none+http").toString()).isEqualTo("none+http://foo.com");

        assertThatThrownBy(foo::port).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> foo.withDefaultPort(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostWithPort() {
        final Endpoint foo = Endpoint.of("foo.com", 80);
        assertThat(foo.type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThat(foo.host()).isEqualTo("foo.com");
        assertThat(foo.ipAddr()).isNull();
        assertThat(foo.ipFamily()).isNull();
        assertThat(foo.hasIpAddr()).isFalse();
        assertThat(foo.port()).isEqualTo(80);
        assertThat(foo.port(42)).isEqualTo(80);
        assertThat(foo.hasPort()).isTrue();
        assertThat(foo.withDefaultPort(42)).isSameAs(foo);
        assertThat(foo.weight()).isEqualTo(1000);
        assertThat(foo.authority()).isEqualTo("foo.com:80");
        assertThat(foo.toUri("none+http").toString()).isEqualTo("none+http://foo.com:80");
    }

    @Test
    void hostWithWeight() {
        final Endpoint foo = Endpoint.of("foo.com", 80).withWeight(500);
        assertThat(foo.weight()).isEqualTo(500);
        assertThat(foo.withWeight(750).weight()).isEqualTo(750);
        assertThat(foo.withWeight(500)).isSameAs(foo);

        foo.withWeight(0);
        assertThatThrownBy(() -> foo.withWeight(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostWithTrailingDot() {
        final Endpoint endpoint = Endpoint.of("foo.com.");
        // The trailing dot should be preserved for the hostname.
        assertThat(endpoint.host()).isEqualTo("foo.com.");
        // The trailing dot should be removed for the authority.
        assertThat(endpoint.authority()).isEqualTo("foo.com");
        assertThat(endpoint.toSocketAddress(80).getHostString()).isEqualTo("foo.com.");
        final Endpoint barEndpoint = endpoint.withHost("bar.com");
        assertThat(barEndpoint.host()).isEqualTo("bar.com");
        assertThat(barEndpoint.authority()).isEqualTo("bar.com");
        assertThat(barEndpoint.toSocketAddress(80).getHostString()).isEqualTo("bar.com");

        final Endpoint bazEndpoint = barEndpoint.withHost("baz.com.");
        assertThat(bazEndpoint.host()).isEqualTo("baz.com.");
        assertThat(bazEndpoint.authority()).isEqualTo("baz.com");
        assertThat(bazEndpoint.toSocketAddress(80).getHostString()).isEqualTo("baz.com.");
    }

    @ParameterizedTest
    @CsvSource({
            "::1, ::1, INET6",
            "[::1], ::1, INET6",
            "::1%eth0, ::1, INET6",
            "[::1%eth0], ::1, INET6",
            "192.168.0.1, 192.168.0.1, INET"
    })
    void hostWithIpAddr(String specifiedIpAddr, String normalizedIpAddr,
                        StandardProtocolFamily expectedIpFamily) {
        final Endpoint foo = Endpoint.of("foo.com");
        assertThat(foo.withIpAddr(specifiedIpAddr).type()).isSameAs(Type.HOSTNAME_AND_IP);
        assertThat(foo.withIpAddr(specifiedIpAddr).authority()).isEqualTo("foo.com");
        assertThat(foo.withIpAddr(specifiedIpAddr).ipAddr()).isEqualTo(normalizedIpAddr);
        assertThat(foo.withIpAddr(specifiedIpAddr).ipFamily()).isEqualTo(expectedIpFamily);
        assertThat(foo.withIpAddr(specifiedIpAddr).hasIpAddr()).isTrue();
        assertThat(foo.withIpAddr(specifiedIpAddr).toUri("none+http").toString())
                .isEqualTo("none+http://foo.com");
    }

    @Test
    void hostWithIpAddrRemoved() {
        final Endpoint foo = Endpoint.of("foo.com").withIpAddr("192.168.0.1");
        assertThat(foo.withIpAddr(null).type()).isSameAs(Type.HOSTNAME_ONLY);
        assertThat(foo.withIpAddr(null).ipAddr()).isNull();
        assertThat(foo.withIpAddr(null).ipFamily()).isNull();
        assertThat(foo.withIpAddr(null).hasIpAddr()).isFalse();
        assertThat(foo.withIpAddr(null).toUri("none+http").toString()).isEqualTo("none+http://foo.com");
    }

    @Test
    void hostWithIpAddrUnchanged() {
        final Endpoint foo = Endpoint.of("foo.com").withIpAddr("192.168.0.1");
        assertThat(foo.withIpAddr("192.168.0.1")).isSameAs(foo);
    }

    @ParameterizedTest
    @CsvSource({
            "no-ip", "[::1", "::1]", "[::1]%eth0", "[::1]:8080", "192.168.0.1:8080", "[192.168.0.1]",
            "[192.168.0.1", "192.168.0.1]", "192.168.0.1%eth0", "[192.168.0.1%eth0]", "%eth0"
    })
    void hostWithBadIpAddr(String specifiedIpAddr) {
        final Endpoint foo = Endpoint.of("foo.com");
        assertThatThrownBy(() -> foo.withIpAddr(specifiedIpAddr)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void badHost() {
        // Should not accept the host name followed by a port.
        assertThatThrownBy(() -> Endpoint.of("foo:80")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Endpoint.of("127.0.0.1:80")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Endpoint.of("[::1]:80")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ipV4() {
        final Endpoint a = Endpoint.of("192.168.0.1");
        assertThat(a.type()).isSameAs(Type.IP_ONLY);
        assertThat(a.host()).isEqualTo("192.168.0.1");
        assertThat(a.isDomainSocket()).isFalse();
        assertThat(a.ipAddr()).isEqualTo("192.168.0.1");
        assertThat(a.ipFamily()).isEqualTo(StandardProtocolFamily.INET);
        assertThat(a.hasIpAddr()).isTrue();
        assertThat(a.authority()).isEqualTo("192.168.0.1");
        assertThat(a.toUri("none+http").toString()).isEqualTo("none+http://192.168.0.1");
        assertThatThrownBy(() -> a.withIpAddr(null)).isInstanceOf(IllegalStateException.class);
        assertThat(a.withIpAddr("192.168.0.1")).isSameAs(a);
        assertThat(a.withIpAddr("192.168.0.2")).isEqualTo(Endpoint.of("192.168.0.2"));

        assertThat(Endpoint.of("192.168.0.1", 80).authority()).isEqualTo("192.168.0.1:80");
    }

    @Test
    void ipV4Parse() {
        final Endpoint a = Endpoint.parse("192.168.0.1:80");
        assertThat(a.type()).isSameAs(Type.IP_ONLY);
        assertThat(a.host()).isEqualTo("192.168.0.1");
        assertThat(a.ipAddr()).isEqualTo("192.168.0.1");
        assertThat(a.ipFamily()).isEqualTo(StandardProtocolFamily.INET);
        assertThat(a.hasIpAddr()).isTrue();
        assertThat(a.port()).isEqualTo(80);
        assertThat(a.authority()).isEqualTo("192.168.0.1:80");
        assertThat(a.toUri("none+http").toString()).isEqualTo("none+http://192.168.0.1:80");
    }

    @Test
    void ipV6() {
        final Endpoint a = Endpoint.of("::1");
        assertThat(a.type()).isSameAs(Type.IP_ONLY);
        assertThat(a.host()).isEqualTo("::1");
        assertThat(a.isDomainSocket()).isFalse();
        assertThat(a.ipAddr()).isEqualTo("::1");
        assertThat(a.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(a.hasIpAddr()).isTrue();
        assertThat(a.authority()).isEqualTo("[::1]");
        assertThat(a.toUri("none+http").toString()).isEqualTo("none+http://[::1]");
        assertThatThrownBy(() -> a.withIpAddr(null)).isInstanceOf(IllegalStateException.class);
        assertThat(a.withIpAddr("::1")).isSameAs(a);
        assertThat(a.withIpAddr("::2")).isEqualTo(Endpoint.of("::2"));
        assertThat(a.withIpAddr("[::1]")).isSameAs(a);
        assertThat(a.withIpAddr("[::2]")).isEqualTo(Endpoint.of("::2"));

        final Endpoint b = Endpoint.of("::1", 80);
        assertThat(b.type()).isSameAs(Type.IP_ONLY);
        assertThat(b.host()).isEqualTo("::1");
        assertThat(b.ipAddr()).isEqualTo("::1");
        assertThat(b.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(b.hasIpAddr()).isTrue();
        assertThat(b.authority()).isEqualTo("[::1]:80");
        assertThat(b.toUri("none+http").toString()).isEqualTo("none+http://[::1]:80");

        // Surrounding '[' and ']' should be handled correctly.
        final Endpoint c = Endpoint.of("[::1]");
        assertThat(c.type()).isSameAs(Type.IP_ONLY);
        assertThat(c.host()).isEqualTo("::1");
        assertThat(c.ipAddr()).isEqualTo("::1");
        assertThat(c.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(c.hasIpAddr()).isTrue();
        assertThat(c.authority()).isEqualTo("[::1]");
        assertThat(c.toUri("none+http").toString()).isEqualTo("none+http://[::1]");

        final Endpoint d = Endpoint.of("[::1]", 80);
        assertThat(d.type()).isSameAs(Type.IP_ONLY);
        assertThat(d.host()).isEqualTo("::1");
        assertThat(d.ipAddr()).isEqualTo("::1");
        assertThat(d.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(d.hasIpAddr()).isTrue();
        assertThat(d.authority()).isEqualTo("[::1]:80");
        assertThat(d.toUri("none+http").toString()).isEqualTo("none+http://[::1]:80");

        // withIpAddr() should handle surrounding '[' and ']' correctly.
        final Endpoint e = Endpoint.of("foo").withIpAddr("[::1]");
        assertThat(e.type()).isSameAs(Type.HOSTNAME_AND_IP);
        assertThat(e.host()).isEqualTo("foo");
        assertThat(e.ipAddr()).isEqualTo("::1");
        assertThat(e.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(e.toUri("none+http").toString()).isEqualTo("none+http://foo");
    }

    @Test
    void ipV6Parse() {
        final Endpoint a = Endpoint.parse("[::1]:80");
        assertThat(a.type()).isSameAs(Type.IP_ONLY);
        assertThat(a.host()).isEqualTo("::1");
        assertThat(a.ipAddr()).isEqualTo("::1");
        assertThat(a.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(a.hasIpAddr()).isTrue();
        assertThat(a.port()).isEqualTo(80);
        assertThat(a.authority()).isEqualTo("[::1]:80");
        assertThat(a.toUri("none+http").toString()).isEqualTo("none+http://[::1]:80");
    }

    @Test
    void domainSocket() {
        final String sockAddr = "unix%3Afoo.sock";
        final List<Function<String, Endpoint>> factoryFunctions =
                ImmutableList.of(Endpoint::of, Endpoint::parse);

        factoryFunctions.forEach(factoryFunc -> {
            final Endpoint e = factoryFunc.apply(sockAddr);
            assertThat(e.type()).isSameAs(Type.DOMAIN_SOCKET);
            assertThat(e.host()).isEqualTo(sockAddr);
            assertThat(e.authority()).isEqualTo(sockAddr);
            assertThat(e.isDomainSocket()).isTrue();
        });
    }

    @Test
    void authorityCache() {
        final Endpoint foo = Endpoint.of("foo.com", 80);
        final String authority1 = foo.authority();
        final String authority2 = foo.authority();
        assertThat(authority1).isSameAs(authority2);
    }

    @Test
    void withPort() {
        final Endpoint foo = Endpoint.of("foo");
        final Endpoint foo80 = Endpoint.of("foo", 80);
        assertThat(foo.withPort(80)).isEqualTo(foo80);
        assertThat(foo80.withPort(80)).isSameAs(foo80);
        assertThatThrownBy(() -> foo.withPort(0)).isInstanceOf(IllegalArgumentException.class)
                                                 .hasMessageContaining("port");
    }

    @Test
    void withoutPort() {
        final Endpoint foo = Endpoint.of("foo");
        final Endpoint foo80 = Endpoint.of("foo", 80);
        assertThat(foo.withoutPort()).isSameAs(foo);
        assertThat(foo80.withoutPort()).isEqualTo(foo);
    }

    @Test
    void withDefaultPort() {
        final Endpoint foo = Endpoint.of("foo");
        final Endpoint foo80 = Endpoint.of("foo", 80);
        assertThat(foo.withDefaultPort(80)).isEqualTo(foo80);
        assertThat(foo80.withDefaultPort(80)).isSameAs(foo80);
        assertThatThrownBy(() -> foo.withDefaultPort(0)).isInstanceOf(IllegalArgumentException.class)
                                                        .hasMessageContaining("defaultPort");

        // Shortcut that accepts a SessionProtocol
        assertThat(foo.withDefaultPort(SessionProtocol.HTTP)).isEqualTo(foo80);
        assertThat(foo80.withDefaultPort(SessionProtocol.HTTP)).isSameAs(foo80);

        // A domain socket endpoint always has the predefined port.
        final Endpoint domainSocketEndpoint = Endpoint.of("unix%3Afoo.sock");
        assertThat(domainSocketEndpoint.withDefaultPort(80)).isSameAs(domainSocketEndpoint);
    }

    @Test
    void withoutDefaultPort() {
        final Endpoint foo = Endpoint.of("foo");
        final Endpoint foo80 = Endpoint.of("foo", 80);
        assertThat(foo.withoutDefaultPort(80)).isSameAs(foo);
        assertThat(foo80.withoutDefaultPort(80)).isEqualTo(foo);
        assertThat(foo80.withoutDefaultPort(8080)).isSameAs(foo80);
        assertThatThrownBy(() -> foo.withoutDefaultPort(0)).isInstanceOf(IllegalArgumentException.class)
                                                           .hasMessageContaining("defaultPort");

        // Shortcut that accepts a SessionProtocol
        assertThat(foo.withoutDefaultPort(SessionProtocol.HTTP)).isSameAs(foo);
        assertThat(foo80.withoutDefaultPort(SessionProtocol.HTTP)).isEqualTo(foo);

        // A domain socket endpoint always has the predefined port.
        final Endpoint domainSocketEndpoint = Endpoint.of("unix%3Afoo.sock");
        assertThat(domainSocketEndpoint.withoutDefaultPort(DomainSocketUtil.DOMAIN_SOCKET_PORT))
                .isSameAs(domainSocketEndpoint);
    }

    @Test
    void withHost() {
        // Simple hostname change
        final Endpoint foo = Endpoint.of("foo", 80).withIpAddr("127.0.0.1");
        assertThat(foo.withHost("bar")).satisfies(e -> {
            assertThat(e.type()).isSameAs(Type.HOSTNAME_AND_IP);
            assertThat(e.host()).isEqualTo("bar");
            assertThat(e.ipAddr()).isEqualTo(foo.ipAddr());
            assertThat(e.port()).isEqualTo(foo.port());
        });

        // When IP address is specified:
        assertThat(foo.withHost("::1")).satisfies(e -> {
            assertThat(e.type()).isSameAs(Type.IP_ONLY);
            assertThat(e.host()).isEqualTo("::1");
            assertThat(e.ipAddr()).isEqualTo("::1");
            assertThat(e.port()).isEqualTo(foo.port());
        });

        // When hostname is set to an IP-only endpoint:
        assertThat(Endpoint.of("127.0.0.1").withHost("foo")).satisfies(e -> {
            assertThat(e.type()).isSameAs(Type.HOSTNAME_AND_IP);
            assertThat(e.host()).isEqualTo("foo");
            assertThat(e.ipAddr()).isEqualTo("127.0.0.1");
            assertThat(e.hasPort()).isFalse();
        });

        // When domain socket address is specified:
        assertThat(foo.withHost("unix%3A%2Ftmp%2Ffoo.sock")).satisfies(e -> {
            assertThat(e.type()).isSameAs(Type.DOMAIN_SOCKET);
            assertThat(e.host()).isEqualTo("unix%3A%2Ftmp%2Ffoo.sock");
            assertThat(e.isDomainSocket()).isTrue();
            assertThat(e.ipAddr()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_IP);
            assertThat(e.port()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_PORT);
        });
    }

    @Test
    void toUri() {
        final Endpoint router = Endpoint.of("192.168.0.1");
        assertThat(router.toUri("none+h1").toString())
                .isEqualTo("none+h1://192.168.0.1");
        assertThat(router.withDefaultPort(80).toUri("none+h1").toString())
                .isEqualTo("none+h1://192.168.0.1:80");

        final Endpoint google = Endpoint.of("google.com");
        assertThat(google.toUri("none+https").toString())
                .isEqualTo("none+https://google.com");
        assertThat(google.toUri(SessionProtocol.HTTPS).toString())
                .isEqualTo("none+https://google.com");
        assertThat(google.withDefaultPort(80).toUri("none+https").toString())
                .isEqualTo("none+https://google.com:80");

        final Endpoint ipv6WithHostName = Endpoint.of("google.com").withIpAddr("[::1]");
        assertThat(ipv6WithHostName.toUri("none+h2").toString())
                .isEqualTo("none+h2://google.com");
        assertThat(ipv6WithHostName.toUri(SessionProtocol.H2).toString())
                .isEqualTo("none+h2://google.com");
        assertThat(ipv6WithHostName.withDefaultPort(80).toUri("none+h2").toString())
                .isEqualTo("none+h2://google.com:80");

        final Endpoint naver = Endpoint.of("naver.com");
        assertThat(naver.toUri("none+https", "/hello").toString())
                .isEqualTo("none+https://naver.com/hello");
        assertThat(naver.toUri(SessionProtocol.HTTPS, "/hello").toString())
                .isEqualTo("none+https://naver.com/hello");

        assertThat(naver.toUri("https", ""))
                .isEqualTo(naver.toUri("https", null));
    }

    @Test
    void equals() {
        final Endpoint a1 = Endpoint.of("a");
        final Endpoint a2 = Endpoint.of("a");

        new EqualsTester()
                .addEqualityGroup(a1, a2)
                .addEqualityGroup(new Object())
                .testEquals();
    }

    @Test
    void portEquals() {
        final Endpoint a = Endpoint.of("a");
        final Endpoint b = Endpoint.of("a", 80);
        final Endpoint c = Endpoint.of("a", 80);
        final Endpoint d = Endpoint.of("a", 81);
        final Endpoint e = Endpoint.of("a", 80).withIpAddr("::1");
        final Endpoint f = Endpoint.of("a", 80).withWeight(500); // Weight not part of comparison
        final Endpoint g = Endpoint.of("g", 80);

        assertThat(a).isNotEqualTo(b);
        assertThat(b).isEqualTo(c);
        assertThat(b).isNotEqualTo(d);
        assertThat(b).isNotEqualTo(e);
        assertThat(b).isEqualTo(f);
        assertThat(b).isNotEqualTo(g);
    }

    @Test
    void ipAddrEquals() {
        final Endpoint a = Endpoint.of("a");
        final Endpoint b = Endpoint.of("a").withIpAddr("::1");
        final Endpoint c = Endpoint.of("a").withIpAddr("::1");
        final Endpoint d = Endpoint.of("a").withIpAddr("::2");
        final Endpoint e = Endpoint.of("a", 80).withIpAddr("::1");
        final Endpoint f = Endpoint.of("a").withIpAddr("::1").withWeight(500); // Weight not part of comparison
        final Endpoint g = Endpoint.of("g").withIpAddr("::1");
        assertThat(a).isNotEqualTo(b);
        assertThat(b).isEqualTo(c);
        assertThat(b).isNotEqualTo(d);
        assertThat(b).isNotEqualTo(e);
        assertThat(b).isEqualTo(f);
        assertThat(b).isNotEqualTo(g);
    }

    @Test
    void testHashCode() {
        assertThat(Endpoint.of("a").hashCode()).isNotZero();
        assertThat(Endpoint.of("a", 80).hashCode()).isNotZero();
        assertThat(Endpoint.of("a").withIpAddr("::1").hashCode()).isNotZero();
        assertThat(Endpoint.of("a", 80).withIpAddr("::1").hashCode()).isNotZero();

        // Weight is not part of comparison.
        final int hash = Endpoint.of("a", 80).withWeight(500).hashCode();
        assertThat(Endpoint.of("a", 80).withWeight(750).hashCode()).isEqualTo(hash);
    }

    @Test
    void testToString() {
        assertThat(Endpoint.of("a").toString()).isEqualTo("Endpoint{a, weight=1000}");
        assertThat(Endpoint.of("a", 80).toString()).isEqualTo("Endpoint{a:80, weight=1000}");
        assertThat(Endpoint.of("a").withIpAddr("::1").toString())
                .isEqualTo("Endpoint{a, ipAddr=::1, weight=1000}");

        // ipAddr is omitted if hostname is an IP address.
        assertThat(Endpoint.of("127.0.0.1").toString()).isEqualTo("Endpoint{127.0.0.1, weight=1000}");
        assertThat(Endpoint.of("::1").toString()).isEqualTo("Endpoint{[::1], weight=1000}");

        // attributes
        final Endpoint endpointWithAttr = Endpoint.of("127.0.0.1").withAttr(AttributeKey.valueOf("test"), 1);
        assertThat(endpointWithAttr.toString())
                .isEqualTo("Endpoint{127.0.0.1, weight=1000, attributes=[test=1]}");
    }

    @Test
    void comparison() {
        assertThat(Endpoint.of("a")).isEqualByComparingTo(Endpoint.of("a"));
        assertThat(Endpoint.of("a")).isLessThan(Endpoint.of("b"));
        assertThat(Endpoint.of("a")).isLessThan(Endpoint.of("a", 8080));
        assertThat(Endpoint.of("a")).isLessThan(Endpoint.of("a").withIpAddr("1.1.1.1"));

        assertThat(Endpoint.of("a", 8080)).isEqualByComparingTo(Endpoint.of("a", 8080));
        assertThat(Endpoint.of("a", 8080)).isGreaterThan(Endpoint.of("a"));
        assertThat(Endpoint.of("a", 8080)).isLessThan(Endpoint.of("a", 8081));

        assertThat(Endpoint.of("a").withIpAddr("1.1.1.1"))
                .isEqualByComparingTo(Endpoint.of("a").withIpAddr("1.1.1.1"));
        assertThat(Endpoint.of("a").withIpAddr("1.1.1.1")).isGreaterThan(Endpoint.of("a"));
        assertThat(Endpoint.of("a").withIpAddr("1.1.1.1"))
                .isLessThan(Endpoint.of("a").withIpAddr("1.1.1.2"));

        // Weight is not part of comparison.
        assertThat(Endpoint.of("a").withWeight(1)).isEqualByComparingTo(Endpoint.of("a").withWeight(2));
    }

    @Test
    void cache() {
        final Endpoint newEndpoint = Endpoint.parse("foo:10");
        final Endpoint cachedEndpoint = Endpoint.parse("foo:10");
        assertThat(cachedEndpoint).isSameAs(newEndpoint);
        final Endpoint differentEndpoint = Endpoint.of("foo", 10);
        assertThat(differentEndpoint).isNotSameAs(cachedEndpoint);
        assertThat(differentEndpoint).isEqualTo(cachedEndpoint);
    }

    @Test
    void inetAddress() throws UnknownHostException {
        final Endpoint endpoint = Endpoint.of("a");

        final InetAddress ipv4Address = InetAddress.getByName("1.1.1.1");
        final Endpoint endpointWithIpv4 = endpoint.withInetAddress(ipv4Address);
        assertThat(endpointWithIpv4.hasIpAddr()).isTrue();
        assertThat(endpointWithIpv4.ipFamily()).isEqualTo(StandardProtocolFamily.INET);
        assertThat(endpointWithIpv4.ipAddr()).isEqualTo("1.1.1.1");

        final InetAddress ipv6Address = InetAddress.getByName("[0:0:0:0:0:0:0:1]");
        final Endpoint endpointWithIpv6 = endpoint.withInetAddress(ipv6Address);
        assertThat(endpointWithIpv6.hasIpAddr()).isTrue();
        assertThat(endpointWithIpv6.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(endpointWithIpv6.ipAddr()).isEqualTo("::1");
    }

    @Test
    void conversionFromSocketAddress() throws Exception {
        // Unresolved InetSocketAddress
        assertThat(Endpoint.of(InetSocketAddress.createUnresolved("foo", 80))).satisfies(e -> {
            assertThat(e.host()).isEqualTo("foo");
            assertThat(e.isDomainSocket()).isFalse();
            assertThat(e.hasPort()).isTrue();
            assertThat(e.port()).isEqualTo(80);
            assertThat(e.hasIpAddr()).isFalse();
        });

        // Resolved InetSocketAddress
        assertThat(Endpoint.of(new InetSocketAddress(
                InetAddress.getByAddress("bar", new byte[] { 127, 0, 0, 42 }), 443))
        ).satisfies(e -> {
            assertThat(e.host()).isEqualTo("bar");
            assertThat(e.isDomainSocket()).isFalse();
            assertThat(e.hasPort()).isTrue();
            assertThat(e.port()).isEqualTo(443);
            assertThat(e.hasIpAddr()).isTrue();
            assertThat(e.ipAddr()).isEqualTo("127.0.0.42");
            assertThat(e.ipFamily()).isEqualTo(StandardProtocolFamily.INET);
        });

        // DomainSocketAddress (Armeria)
        assertThat(Endpoint.of(DomainSocketAddress.of("/foo.sock"))).satisfies(e -> {
            assertThat(e.host()).isEqualTo("unix%3A%2Ffoo.sock");
            assertThat(e.isDomainSocket()).isTrue();
            assertThat(e.ipAddr()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_IP);
            assertThat(e.port()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_PORT);
        });

        // DomainSocketAddress (Netty)
        assertThat(Endpoint.of(new io.netty.channel.unix.DomainSocketAddress("/bar.sock"))).satisfies(e -> {
            assertThat(e.host()).isEqualTo("unix%3A%2Fbar.sock");
            assertThat(e.isDomainSocket()).isTrue();
            assertThat(e.ipAddr()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_IP);
            assertThat(e.port()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_PORT);
        });
    }

    @Test
    void conversionToSocketAddress() throws Exception {
        // InetSocketAddress
        assertThat(Endpoint.of("foo").toSocketAddress(42)).isEqualTo(
                InetSocketAddress.createUnresolved("foo", 42));
        assertThat(Endpoint.of("bar", 4242).toSocketAddress(42)).isEqualTo(
                InetSocketAddress.createUnresolved("bar", 4242));
        assertThat(Endpoint.of("baz").withIpAddr("127.0.0.1").toSocketAddress(443)).isEqualTo(
                new InetSocketAddress(InetAddress.getByAddress("baz", new byte[] { 127, 0, 0, 1 }), 443));

        // DomainSocketAddress
        assertThat(Endpoint.of("unix%3A%2Ffoo.sock").toSocketAddress(0)).isEqualTo(
                DomainSocketAddress.of(Paths.get("/foo.sock")));
    }

    @Test
    void socketAddressCache() {
        final Endpoint endpointWithPort = Endpoint.of("foo", 42);
        assertThat(endpointWithPort.toSocketAddress(-1))
                .isSameAs(endpointWithPort.toSocketAddress(1));

        final Endpoint endpointWithoutPort = Endpoint.of("foo");
        assertThat(endpointWithoutPort.toSocketAddress(80))
                .isNotSameAs(endpointWithoutPort.toSocketAddress(80));

        final Endpoint endpointWithDomainSocket = Endpoint.of("unix%3A%2Ffoo.sock");
        assertThat(endpointWithDomainSocket.toSocketAddress(-1))
                .isSameAs(endpointWithDomainSocket.toSocketAddress(1));
    }

    @Test
    void socketAddressPrecache() throws Exception {
        final DomainSocketAddress domainSocketAddress = DomainSocketAddress.of(Paths.get("/foo.sock"));
        assertThat(Endpoint.of(domainSocketAddress).toSocketAddress(-1))
                .isSameAs(domainSocketAddress);

        final InetSocketAddress unresolvedSocketAddress = InetSocketAddress.createUnresolved("foo", 42);
        assertThat(Endpoint.of(unresolvedSocketAddress).toSocketAddress(-1))
                .isSameAs(unresolvedSocketAddress);

        final InetSocketAddress resolvedSocketAddress = new InetSocketAddress(
                InetAddress.getByAddress("foo", new byte[] { 127, 0, 0, 1 }), 42);
        assertThat(Endpoint.of(resolvedSocketAddress).toSocketAddress(-1))
                .isSameAs(resolvedSocketAddress);

        // Should not be cached because the normalized IP address is different from the original hostname.
        final InetSocketAddress uncacheableSocketAddress = new InetSocketAddress(
                InetAddress.getByAddress("1.1.1.1", // A mismatching IP string in hostname
                                         new byte[] { 127, 0, 0, 1 }), 42);
        final InetSocketAddress normalizedSocketAddress = Endpoint.of(uncacheableSocketAddress)
                                                                  .toSocketAddress(-1);
        assertThat(normalizedSocketAddress).isNotSameAs(uncacheableSocketAddress);
        assertThat(normalizedSocketAddress.getHostString()).isEqualTo("127.0.0.1");
        assertThat(normalizedSocketAddress.getAddress().getHostAddress())
                .isEqualTo(uncacheableSocketAddress.getAddress().getHostAddress());
    }

    @Test
    void domainSocketAlwaysHasPredefinedIpAddr() {
        final Endpoint endpoint = Endpoint.of("unix%3A%2Ffoo.sock");
        assertThat(endpoint.type()).isSameAs(Type.DOMAIN_SOCKET);
        assertThat(endpoint.withIpAddr("127.0.0.1").ipAddr()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_IP);
        assertThat(endpoint.withIpAddr("127.0.0.1")).isSameAs(endpoint);
        assertThat(endpoint.withIpAddr(null).ipAddr()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_IP);
        assertThat(endpoint.withIpAddr(null)).isSameAs(endpoint);
    }

    @Test
    void domainSocketAlwaysHasPredefinedPort() {
        final Endpoint endpoint = Endpoint.of("unix%3A%2Ffoo.sock", 8080);
        assertThat(endpoint.port()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_PORT);
        assertThat(endpoint.withPort(8080).port()).isEqualTo(DomainSocketUtil.DOMAIN_SOCKET_PORT);
        assertThat(endpoint.withPort(8080)).isSameAs(endpoint);
    }

    @Test
    void setAndGetAttr() {
        final Endpoint endpointA = Endpoint.parse("a");

        final AttributeKey<String> key1 = AttributeKey.valueOf("key1");
        final AttributeKey<String> key2 = AttributeKey.valueOf("value2");
        final AttributeKey<Object> objKey = AttributeKey.valueOf("objKey");
        final Object objValue = new Object();
        final Endpoint endpointB = endpointA.withAttr(key1, "value1")
                                            .withAttr(key2, "value2")
                                            .withAttr(objKey, objValue)
                                            .withAttr(key1, "value1-1");

        assertThat(endpointB).isNotSameAs(endpointA);
        assertThat(endpointA.attr(key1)).isNull();
        assertThat(endpointB.attr(key1)).isEqualTo("value1-1");
        assertThat(endpointB.attr(key2)).isEqualTo("value2");

        // key with same value
        assertThat(endpointB.withAttr(objKey, objValue)).isSameAs(endpointB);
        assertThat(endpointB.withAttr(AttributeKey.valueOf("keyNotFound"), null)).isSameAs(endpointB);

        // value remove
        final Endpoint endpointC = endpointB.withAttr(AttributeKey.valueOf("key1"), null);
        assertThat(endpointC).isNotSameAs(endpointB);
        assertThat(endpointC.attr(key1)).isNull();
    }

    @Test
    void attrs() {
        final Endpoint endpoint = Endpoint.parse("a");
        assertThat(endpoint.attrs().attrs()).isExhausted();

        final AttributesBuilder attrs = Attributes.builder();
        final AttributeKey<String> key1 = AttributeKey.valueOf("key1");
        final AttributeKey<String> key2 = AttributeKey.valueOf("key2");

        attrs.set(key1, "value1");
        attrs.set(key2, "value2");

        final AttributesBuilder attrs2 = Attributes.builder();
        final AttributeKey<String> key3 = AttributeKey.valueOf("key3");
        attrs2.set(key1, "value1-2");
        attrs2.set(key3, "value3");

        final Endpoint endpointB = endpoint.withAttrs(attrs.build());
        final Endpoint endpointC = endpointB.withAttrs(attrs2.build());

        assertThat(endpointB.attr(key1))
                .isEqualTo("value1");
        assertThat(endpointB.attr(key2))
                .isEqualTo("value2");
        assertThat(endpointB.attrs().attrs())
                .toIterable()
                .containsExactlyInAnyOrder(Maps.immutableEntry(key1, "value1"),
                                           Maps.immutableEntry(key2, "value2"));

        // `attrs` should be replaced with `attrs2`
        assertThat(endpointC.attr(key1)).isEqualTo("value1-2");
        assertThat(endpointC.attr(key2)).isNull();
        assertThat(endpointC.attr(key3)).isEqualTo("value3");
        assertThat(endpointC.attrs().attrs())
                .toIterable()
                .containsExactlyInAnyOrder(Maps.immutableEntry(key1, "value1-2"),
                                           Maps.immutableEntry(key3, "value3"));

        // Reset attrs with an empty attributes.
        final Endpoint newEndpointB = endpointB.withAttrs(Attributes.of());
        assertThat(newEndpointB.attrs().isEmpty()).isTrue();

        final Endpoint sameEndpoint = endpoint.withAttrs(Attributes.of());
        assertThat(sameEndpoint).isSameAs(endpoint);
    }
}
