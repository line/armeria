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
package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.util.Inet4AddressBlock.ipv6ToIpv4Address;
import static java.util.Objects.requireNonNull;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.function.Predicate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap.Builder;

import io.netty.util.NetUtil;

/**
 * A utility class which provides factory methods in order to easily create a {@link Predicate} of an
 * {@link InetAddress}.
 */
public final class InetAddressPredicates {

    /**
     * A {@link Splitter} for splitting an IP address.
     */
    private static final Splitter dotSplitter = Splitter.on('.').limit(4);

    /**
     * A table for converting an IP decimal value to the number of bits.
     */
    private static final Map<Integer, Integer> ipDecimalToMaskBits = new Builder<Integer, Integer>()
            .put(0xFF, 8)
            .put(0xFE, 7)
            .put(0xFC, 6)
            .put(0xF8, 5)
            .put(0xF0, 4)
            .put(0xE0, 3)
            .put(0xC0, 2)
            .put(0x80, 1)
            .put(0x00, 0)
            .build();

    /**
     * Returns a {@link Predicate} which returns {@code true} if the given {@link InetAddress} equals to
     * the specified {@code address}.
     *
     * @param address the expected {@link InetAddress}
     */
    public static Predicate<InetAddress> ofExact(InetAddress address) {
        requireNonNull(address, "address");
        if (address instanceof Inet4Address) {
            return ofCidr(address, 32);
        }
        if (address instanceof Inet6Address) {
            return ofCidr(address, 128);
        }
        throw new IllegalArgumentException("Invalid InetAddress type: " + address.getClass().getName());
    }

    /**
     * Returns a {@link Predicate} which returns {@code true} if the given {@link InetAddress} equals to
     * the specified {@code address}.
     *
     * @param address the expected IP address string, e.g. {@code 10.0.0.1}
     */
    public static Predicate<InetAddress> ofExact(String address) {
        requireNonNull(address, "address");
        try {
            final InetAddress inetAddress = InetAddress.getByName(address);
            return ofExact(inetAddress);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid address: " + address, e);
        }
    }

    /**
     * Returns a {@link Predicate} which returns {@code true} if the given {@link InetAddress} is in the
     * range of a <a href="https://datatracker.ietf.org/doc/rfc4632/">Classless Inter-domain Routing (CIDR)</a> block.
     *
     * @param baseAddress the base {@link InetAddress} of a CIDR notation
     * @param maskBits the number of significant bits which describes its network portion
     */
    public static Predicate<InetAddress> ofCidr(InetAddress baseAddress, int maskBits) {
        requireNonNull(baseAddress, "baseAddress");
        checkArgument(maskBits >= 0, "maskBits: %s (expected: >= 0)", maskBits);
        return ofCidr(baseAddress, maskBits, maskBits);
    }

    /**
     * Returns a {@link Predicate} which returns {@code true} if the given {@link InetAddress} is in the
     * range of a <a href="https://datatracker.ietf.org/doc/rfc4632/">Classless Inter-domain Routing (CIDR)</a> block.
     *
     * @param baseAddress the base {@link InetAddress} of a CIDR notation
     * @param subnetMask the subnet mask, e.g. {@code 255.255.255.0}
     */
    public static Predicate<InetAddress> ofCidr(InetAddress baseAddress, String subnetMask) {
        requireNonNull(baseAddress, "baseAddress");
        requireNonNull(subnetMask, "subnetMask");
        checkArgument(NetUtil.isValidIpV4Address(subnetMask),
                      "subnetMask: %s (expected: an IPv4 address string)", subnetMask);
        final int maskBits = toMaskBits(subnetMask);
        return ofCidr(baseAddress, maskBits, maskBits + 96);
    }

    /**
     * Returns a {@link Predicate} which returns {@code true} if the given {@link InetAddress} is in the
     * range of a <a href="https://datatracker.ietf.org/doc/rfc4632/">Classless Inter-domain Routing (CIDR)</a> block.
     *
     * @param cidr the CIDR notation of an address block, e.g. {@code 10.0.0.0/8}, {@code 192.168.1.0/24},
     *             {@code 1080:0:0:0:8:800:200C:4100/120}. If it's an exact IP address such as
     *             {@code 10.1.1.7} or {@code 1080:0:0:0:8:800:200C:4100}, the mask bits is considered as
     *             {@code 32} for IPv4 or {@code 128} for IPv6.
     */
    public static Predicate<InetAddress> ofCidr(String cidr) {
        requireNonNull(cidr, "cidr");

        final int delim = cidr.indexOf('/');

        final InetAddress baseAddress;
        final int maskBits;
        try {
            // Exact IP address.
            if (delim < 0) {
                baseAddress = InetAddress.getByName(cidr);
                maskBits = baseAddress instanceof Inet4Address ? 32 : 128;
            } else {
                baseAddress = InetAddress.getByName(cidr.substring(0, delim));
                final String subnetMask = cidr.substring(delim + 1);
                checkArgument(!subnetMask.isEmpty(), "Invalid CIDR notation: %s", cidr);
                if (NetUtil.isValidIpV4Address(subnetMask)) {
                    maskBits = toMaskBits(subnetMask);
                    return ofCidr(baseAddress, maskBits, maskBits + 96);
                }
                maskBits = Integer.parseInt(subnetMask);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CIDR notation: " + cidr, e);
        }
        return ofCidr(baseAddress, maskBits, maskBits);
    }

    private static Predicate<InetAddress> ofCidr(InetAddress baseAddress,
                                                 int inet4MaskBits,
                                                 int inet6MaskBits) {
        if (baseAddress instanceof Inet4Address) {
            return new Inet4AddressBlock((Inet4Address) baseAddress, inet4MaskBits);
        }
        if (baseAddress instanceof Inet6Address) {
            final byte[] bytes = ipv6ToIpv4Address((Inet6Address) baseAddress);
            if (bytes == null) {
                return new Inet6AddressBlock((Inet6Address) baseAddress, inet6MaskBits);
            }

            try {
                final Inet4Address inet4Address = (Inet4Address) InetAddress.getByAddress(bytes);
                return new Inet4AddressBlock(inet4Address, inet6MaskBits - 96);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid address: " + baseAddress.getHostAddress(), e);
            }
        }
        throw new IllegalArgumentException(
                "Unknown baseAddress type: " + baseAddress.getClass().getName() +
                " (expected: " + Inet4Address.class.getSimpleName() +
                " or " + Inet6Address.class.getSimpleName() + ')');
    }

    @VisibleForTesting
    static int toMaskBits(String subnetMask) {
        int maskBits = 0;
        boolean expectZero = false;
        for (final String ipValue : dotSplitter.split(subnetMask)) {
            final int num = Integer.parseInt(ipValue);
            if (expectZero && num != 0) {
                throw new IllegalArgumentException("Invalid subnet mask address: " + subnetMask);
            }

            final int bits = ipDecimalToMaskBits.getOrDefault(num, -1);
            if (bits < 0) {
                throw new IllegalArgumentException("Invalid subnet mask address: " + subnetMask);
            }

            maskBits += bits;
            if (bits != 8 && !expectZero) {
                expectZero = true;
            }
        }
        return maskBits;
    }

    private InetAddressPredicates() {}
}
