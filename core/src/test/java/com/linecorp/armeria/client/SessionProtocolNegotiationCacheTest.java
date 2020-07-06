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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.client.SessionProtocolNegotiationCache;

import io.netty.util.NetUtil;

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

    private static InetSocketAddress toRemoteAddress(Endpoint endpoint) throws UnknownHostException {
        final String ipAddr;
        if (endpoint.hasIpAddr()) {
            ipAddr = endpoint.ipAddr();
        } else {
            ipAddr = "127.0.0.1"; // Do not resolve the host name but just use local address for test.
        }
        return toRemoteAddress(endpoint.host(), ipAddr, endpoint.port());
    }

    private static InetSocketAddress toRemoteAddress(
            String host, String ipAddr, int port) throws UnknownHostException {
        final InetAddress inetAddr = InetAddress.getByAddress(
                host, NetUtil.createByteArrayFromIpAddressString(ipAddr));
        return new InetSocketAddress(inetAddr, port);
    }
}
