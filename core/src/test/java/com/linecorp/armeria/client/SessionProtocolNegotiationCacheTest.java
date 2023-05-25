/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.DomainSocketAddress;

class SessionProtocolNegotiationCacheTest {

    @BeforeEach
    @AfterEach
    void clearCache() {
        SessionProtocolNegotiationCache.clear();
    }

    @Test
    void unsupported() throws UnknownHostException {
        final Endpoint a = Endpoint.of("a", 80);
        final InetSocketAddress aRemoteAddress = toRemoteAddress(a);
        assertThat(SessionProtocolNegotiationCache.isUnsupported(a, H2C)).isFalse(); // Do not know yet.

        SessionProtocolNegotiationCache.setUnsupported(aRemoteAddress, H2C);
        assertThat(SessionProtocolNegotiationCache.isUnsupported(a, H2C)).isTrue();
        assertThat(SessionProtocolNegotiationCache.isUnsupported(a, H2)).isFalse();

        final Endpoint b = Endpoint.of("b", 443).withIpAddr("127.0.0.2");
        final InetSocketAddress bRemoteAddress = toRemoteAddress(b);
        assertThat(SessionProtocolNegotiationCache.isUnsupported(b, H2)).isFalse(); // Do not know yet.

        SessionProtocolNegotiationCache.setUnsupported(bRemoteAddress, H2);
        assertThat(SessionProtocolNegotiationCache.isUnsupported(b, H2)).isTrue();
        assertThat(SessionProtocolNegotiationCache.isUnsupported(b, H2C)).isFalse();

        final Endpoint ip = Endpoint.of("127.0.0.3", 443);
        final InetSocketAddress ipRemoteAddress = toRemoteAddress(ip);
        assertThat(SessionProtocolNegotiationCache.isUnsupported(ip, H2)).isFalse(); // Do not know yet.

        SessionProtocolNegotiationCache.setUnsupported(ipRemoteAddress, H2);
        assertThat(SessionProtocolNegotiationCache.isUnsupported(ip, H2)).isTrue();
        assertThat(SessionProtocolNegotiationCache.isUnsupported(ip, H2C)).isFalse();
    }

    /**
     * Makes sure the cache keys created via different paths are the same.
     */
    @Test
    void hostnameKeyGeneration() throws Exception {
        final String expectedKey = "foo.com|80";
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("foo.com"), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("foo.com", 80), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("foo.com").withIpAddr("127.0.0.1"), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("foo.com", 80).withIpAddr("127.0.0.1"), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                InetSocketAddress.createUnresolved("foo.com", 80)))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                new InetSocketAddress(InetAddress.getByAddress("foo.com",
                                                               new byte[] { 127, 0, 0, 1 }), 80)))
                .isEqualTo(expectedKey);
    }

    @Test
    void ipV4AddrKeyGeneration() throws Exception {
        final String expectedKey = "127.0.0.1|8080";
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("127.0.0.1", 8080), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                new InetSocketAddress("127.0.0.1", 8080)))
                .isEqualTo(expectedKey);
    }

    @Test
    void ipV6AddrKeyGeneration() throws Exception {
        final String expectedKey = "::1|8080";
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("::1", 8080), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("0:0:0:0:0:0:0:1", 8080), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                new InetSocketAddress("::1", 8080)))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                new InetSocketAddress("0:0:0:0:0:0:0:1", 8080)))
                .isEqualTo(expectedKey);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void domainSocketKeyGeneration() {
        final String expectedKey = "unix%3A%2Fvar%2Frun%2Ffoo.sock";
        assertThat(SessionProtocolNegotiationCache.key(
                Endpoint.of("unix%3A%2Fvar%2Frun%2Ffoo.sock"), SessionProtocol.H1C))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                DomainSocketAddress.of(Paths.get("/var/run/foo.sock"))))
                .isEqualTo(expectedKey);
        assertThat(SessionProtocolNegotiationCache.key(
                new io.netty.channel.unix.DomainSocketAddress("/var/run/foo.sock")))
                .isEqualTo(expectedKey);
    }

    private static InetSocketAddress toRemoteAddress(Endpoint endpoint) throws UnknownHostException {
        if (!endpoint.hasIpAddr()) {
            endpoint = endpoint.withIpAddr("127.0.0.1");
        }
        return endpoint.toSocketAddress(-1);
    }
}
