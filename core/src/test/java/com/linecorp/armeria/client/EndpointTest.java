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

import java.net.StandardProtocolFamily;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;

class EndpointTest {

    @Test
    void parse() {
        final Endpoint foo = Endpoint.parse("foo");
        assertThat(foo).isEqualTo(Endpoint.of("foo"));
        assertThatThrownBy(foo::port).isInstanceOf(IllegalStateException.class);
        assertThat(foo.weight()).isEqualTo(1000);
        assertThat(foo.ipAddr()).isNull();
        assertThat(foo.ipFamily()).isNull();
        assertThat(foo.hasIpAddr()).isFalse();
        assertThat(foo.hasPort()).isFalse();
        assertThat(foo.toUri("none+http").toString()).isEqualTo("none+http://foo");

        final Endpoint bar = Endpoint.parse("bar:80");
        assertThat(bar).isEqualTo(Endpoint.of("bar", 80));
        assertThat(bar.port()).isEqualTo(80);
        assertThat(bar.weight()).isEqualTo(1000);
        assertThat(bar.ipAddr()).isNull();
        assertThat(bar.ipFamily()).isNull();
        assertThat(bar.hasIpAddr()).isFalse();
        assertThat(bar.hasPort()).isTrue();
        assertThat(bar.toUri("none+http").toString()).isEqualTo("none+http://bar:80");
    }

    @Test
    void hostWithoutPort() {
        final Endpoint foo = Endpoint.of("foo.com");
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
    void hostWithIpAddr() {
        final Endpoint foo = Endpoint.of("foo.com").withIpAddr("192.168.0.1");
        assertThat(foo.authority()).isEqualTo("foo.com");
        assertThat(foo.ipAddr()).isEqualTo("192.168.0.1");
        assertThat(foo.ipFamily()).isEqualTo(StandardProtocolFamily.INET);
        assertThat(foo.hasIpAddr()).isTrue();
        assertThat(foo.toUri("none+http").toString()).isEqualTo("none+http://foo.com");
        assertThat(foo.withIpAddr(null).ipAddr()).isNull();
        assertThat(foo.withIpAddr(null).toUri("none+http").toString()).isEqualTo("none+http://foo.com");
        assertThat(foo.withIpAddr("::1").authority()).isEqualTo("foo.com");
        assertThat(foo.withIpAddr("::1").ipAddr()).isEqualTo("::1");
        assertThat(foo.withIpAddr("::1").ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(foo.withIpAddr("::1").hasIpAddr()).isTrue();
        assertThat(foo.withIpAddr("::1").toUri("none+http").toString()).isEqualTo("none+http://foo.com");
        assertThat(foo.withIpAddr("192.168.0.1")).isSameAs(foo);
        assertThat(foo.withIpAddr("192.168.0.2").authority()).isEqualTo("foo.com");
        assertThat(foo.withIpAddr("192.168.0.2").ipAddr()).isEqualTo("192.168.0.2");
        assertThat(foo.withIpAddr("192.168.0.2").ipFamily()).isEqualTo(StandardProtocolFamily.INET);
        assertThat(foo.withIpAddr("192.168.0.2").hasIpAddr()).isTrue();
        assertThat(foo.withIpAddr("192.168.0.2").toUri("none+http").toString())
                .isEqualTo("none+http://foo.com");

        assertThatThrownBy(() -> foo.withIpAddr("no-ip")).isInstanceOf(IllegalArgumentException.class);
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
        assertThat(a.host()).isEqualTo("192.168.0.1");
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
        assertThat(a.host()).isEqualTo("::1");
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
        assertThat(b.host()).isEqualTo("::1");
        assertThat(b.ipAddr()).isEqualTo("::1");
        assertThat(b.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(b.hasIpAddr()).isTrue();
        assertThat(b.authority()).isEqualTo("[::1]:80");
        assertThat(b.toUri("none+http").toString()).isEqualTo("none+http://[::1]:80");

        // Surrounding '[' and ']' should be handled correctly.
        final Endpoint c = Endpoint.of("[::1]");
        assertThat(c.host()).isEqualTo("::1");
        assertThat(c.ipAddr()).isEqualTo("::1");
        assertThat(c.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(c.hasIpAddr()).isTrue();
        assertThat(c.authority()).isEqualTo("[::1]");
        assertThat(c.toUri("none+http").toString()).isEqualTo("none+http://[::1]");

        final Endpoint d = Endpoint.of("[::1]", 80);
        assertThat(d.host()).isEqualTo("::1");
        assertThat(d.ipAddr()).isEqualTo("::1");
        assertThat(d.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(d.hasIpAddr()).isTrue();
        assertThat(d.authority()).isEqualTo("[::1]:80");
        assertThat(d.toUri("none+http").toString()).isEqualTo("none+http://[::1]:80");

        // withIpAddr() should handle surrounding '[' and ']' correctly.
        final Endpoint e = Endpoint.of("foo").withIpAddr("[::1]");
        assertThat(e.host()).isEqualTo("foo");
        assertThat(e.ipAddr()).isEqualTo("::1");
        assertThat(e.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(e.toUri("none+http").toString()).isEqualTo("none+http://foo");
    }

    @Test
    void ipV6Parse() {
        final Endpoint a = Endpoint.parse("[::1]:80");
        assertThat(a.host()).isEqualTo("::1");
        assertThat(a.ipAddr()).isEqualTo("::1");
        assertThat(a.ipFamily()).isEqualTo(StandardProtocolFamily.INET6);
        assertThat(a.hasIpAddr()).isTrue();
        assertThat(a.port()).isEqualTo(80);
        assertThat(a.authority()).isEqualTo("[::1]:80");
        assertThat(a.toUri("none+http").toString()).isEqualTo("none+http://[::1]:80");
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

        assertThat(a1).isNotEqualTo(new Object());
        assertThat(a1).isEqualTo(a1);
        assertThat(a1).isEqualTo(a2);
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
}
