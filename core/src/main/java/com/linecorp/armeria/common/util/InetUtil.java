/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.util;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;

/**
 * A utility class which provides useful methods for {@link InetAddress}.
 */
public final class InetUtil {

    // Forked from InetUtils in spring-cloud-common 3.0.0.M1 at e7bb7ed3ae19a91c6fa7b3b698dd9788f70df7d4
    // - Use CIDR in isPreferredAddress instead of regular expression.

    private static final Logger logger = LoggerFactory.getLogger(InetUtil.class);

    @Nullable
    private static InetAddress firstNonLoopbackIpV4Address;

    /**
     * Returns the non loopback IPv4 address whose {@link NetworkInterface#getIndex()} is the lowest.
     */
    @Nullable
    public static InetAddress findFirstNonLoopbackIpV4Address() {
        if (firstNonLoopbackIpV4Address != null) {
            return firstNonLoopbackIpV4Address;
        }
        InetAddress result = null;
        try {
            int lowest = Integer.MAX_VALUE;
            for (final Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
                 nics.hasMoreElements();) {
                final NetworkInterface ifc = nics.nextElement();
                if (ifc.isUp()) {
                    // The NIC whose index is the lowest will be likely the valid IPv4 address.
                    // See https://github.com/spring-cloud/spring-cloud-commons/issues/82.
                    if (ifc.getIndex() < lowest || result == null) {
                        lowest = ifc.getIndex();
                    } else if (result != null) {
                        continue;
                    }

                    for (final Enumeration<InetAddress> addrs = ifc.getInetAddresses();
                         addrs.hasMoreElements();) {
                        final InetAddress address = addrs.nextElement();
                        if (address instanceof Inet4Address &&
                            !address.isLoopbackAddress() &&
                            isPreferredAddress(address)) {
                            result = address;
                        }
                    }
                }
            }
        } catch (IOException ex) {
            logger.error("Cannot get first non-loopback address", ex);
        }

        if (result != null) {
            return firstNonLoopbackIpV4Address = result;
        }

        try {
            return firstNonLoopbackIpV4Address = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            logger.warn("Unable to retrieve localhost");
        }

        return null;
    }

    private static boolean isPreferredAddress(InetAddress address) {
        final List<Predicate<InetAddress>> predicates = Flags.preferredIpV4Cidr();
        if (predicates.isEmpty()) {
            return true;
        }
        for (Predicate<InetAddress> predicate : predicates) {
            if (predicate.test(address)) {
                return true;
            }
        }

        return false;
    }

    private InetUtil() {}
}
