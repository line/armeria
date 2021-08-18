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
import static java.util.Objects.requireNonNull;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class Inet4AddressBlock implements Predicate<InetAddress> {

    private static final byte[] localhost = { 127, 0, 0, 1 };

    private final Inet4Address baseAddress;
    private final int maskBits;
    private final int lowerBound;
    private final int upperBound;

    Inet4AddressBlock(Inet4Address baseAddress, int maskBits) {
        this.baseAddress = requireNonNull(baseAddress, "baseAddress");
        checkArgument(maskBits >= 0 && maskBits <= 32,
                      "maskBits: %s (expected: 0-32)", maskBits);
        this.maskBits = maskBits;

        if (maskBits == 32) {
            lowerBound = upperBound = ipv4AddressToInt(baseAddress.getAddress());
        } else if (maskBits == 0) {
            lowerBound = upperBound = 0;
        } else {
            // Calculate the lower and upper bounds of this address block.
            // e.g. mask is 256(0x00000100) if maskBits is 24.
            final int mask = 1 << 32 - maskBits;
            // e.g. (-mask) is 0xFFFFFF00 if mask is 256.
            lowerBound = ipv4AddressToInt(baseAddress.getAddress()) & -mask;
            // e.g. (mask-1) is 0x000000FF if mask is 256.
            upperBound = lowerBound + mask - 1;
        }
    }

    @Override
    public boolean test(InetAddress address) {
        requireNonNull(address, "address");
        if (maskBits == 0) {
            return true;
        }

        final byte[] bytes;
        if (address instanceof Inet6Address) {
            bytes = ipv6ToIpv4Address((Inet6Address) address);
        } else {
            bytes = address.getAddress();
        }
        if (bytes == null) {
            return false;
        }

        final int addr = ipv4AddressToInt(bytes);
        return addr >= lowerBound && addr <= upperBound;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("baseAddress", baseAddress)
                          .add("maskBits", maskBits)
                          .add("lowerBound", "0x" + Integer.toHexString(lowerBound))
                          .add("upperBound", "0x" + Integer.toHexString(upperBound))
                          .toString();
    }

    /**
     * Returns IPv4 byte representation of the specified {@link Inet6Address}. {@code null} is returned
     * if the specified {@link Inet6Address} is not able to be converted to IPv4.
     */
    @Nullable
    static byte[] ipv6ToIpv4Address(Inet6Address address) {
        final byte[] addr = address.getAddress();
        assert addr.length == 16
                : "the length of " + address.getClass().getSimpleName() + ": " + addr.length;
        // The first 10 bytes should be 0.
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0x00) {
                return null;
            }
        }

        if (addr[10] == 0x00 && addr[11] == 0x00) {
            if (addr[12] == 0x00 && addr[13] == 0x00 && addr[14] == 0x00 && addr[15] == 0x01) {
                return localhost;
            }
            return new byte[] { addr[12], addr[13], addr[14], addr[15] };
        }

        if (addr[10] == (byte) 0xFF && addr[11] == (byte) 0xFF) {
            return new byte[] { addr[12], addr[13], addr[14], addr[15] };
        }

        return null;
    }

    /**
     * Returns the integer representation of the specified {@code address} which is a byte array of
     * an IPv4 address.
     */
    private static int ipv4AddressToInt(byte[] address) {
        int val = 0;
        for (final byte a : address) {
            val <<= 8;
            val |= a & 0xFF;
        }
        return val;
    }
}
