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

import java.net.InetAddress;
import java.net.InetSocketAddress;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.NetUtil;

public final class IpAddrUtil {

    @Nullable
    public static String normalize(@Nullable String ipAddr) {
        if (ipAddr == null) {
            return null;
        }

        final byte[] array = NetUtil.createByteArrayFromIpAddressString(ipAddr);
        if (array == null) {
            // Not an IP address
            return null;
        }

        return NetUtil.bytesToIpAddress(array);
    }

    public static boolean isCreatedWithIpAddressOnly(InetSocketAddress socketAddress) {
        if (socketAddress.isUnresolved()) {
            return false;
        }

        final InetAddress inetAddress = socketAddress.getAddress();
        // If hostname and host address are the same, it was created with an IP address
        return socketAddress.getHostString().equals(inetAddress.getHostAddress());
    }

    private IpAddrUtil() {}
}
