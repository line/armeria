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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

class IpAddrUtilTest {

    @Test
    void testIsCreatedWithIpAddressOnly() throws UnknownHostException {
        InetSocketAddress inetSocketAddress = new InetSocketAddress("foo.com", 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isFalse();

        inetSocketAddress = InetSocketAddress.createUnresolved("foo.com", 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isFalse();

        inetSocketAddress = new InetSocketAddress("1.2.3.4", 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isTrue();

        InetAddress inetAddress = InetAddress.getByName("1.2.3.4");
        inetSocketAddress = new InetSocketAddress(inetAddress, 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isTrue();

        inetAddress = InetAddress.getByName("0.0.0.0");
        inetSocketAddress = new InetSocketAddress(inetAddress, 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isTrue();

        inetAddress = InetAddress.getByName("::");
        inetSocketAddress = new InetSocketAddress(inetAddress, 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isTrue();

        inetAddress = InetAddress.getByAddress("foo.com", new byte[] { 1, 2, 3, 4 });
        inetSocketAddress = new InetSocketAddress(inetAddress, 8080);
        assertThat(IpAddrUtil.isCreatedWithIpAddressOnly(inetSocketAddress)).isFalse();
    }
}
