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

final class Inet6AddressBlock implements Predicate<InetAddress> {

    private final Inet6Address baseAddress;
    private final int maskBits;

    private final long[] lowerBound;
    private final long[] upperBound;

    private final boolean[] skipCompare = new boolean[2];

    Inet6AddressBlock(Inet6Address baseAddress, int maskBits) {
        this.baseAddress = requireNonNull(baseAddress, "baseAddress");
        checkArgument(maskBits >= 0 && maskBits <= 128,
                      "maskBits: %s (expected: 0-128)", maskBits);
        this.maskBits = maskBits;

        if (maskBits == 128) {
            lowerBound = upperBound = ipv6AddressToLongArray(baseAddress);
        } else if (maskBits == 0) {
            lowerBound = upperBound = new long[] { 0, 0 };
        } else {
            // Calculate the lower and upper bounds of this address block.
            // See Inet4AddressBlock if you want to know how they are calculated.
            final long[] mask = calculateMask(maskBits);
            lowerBound = calculateLowerBound(baseAddress, mask);
            upperBound = calculateUpperBound(lowerBound, mask);

            // If lowerBound is 0 and upperBound is 0xFFFFFFFFFFFFFFFF, skip comparing the value because
            // it covers all values.
            skipCompare[0] = lowerBound[0] == 0L && upperBound[0] == -1L;
            skipCompare[1] = lowerBound[1] == 0L && upperBound[1] == -1L;
        }
    }

    @Override
    public boolean test(InetAddress address) {
        requireNonNull(address, "address");
        if (address instanceof Inet4Address) {
            // This block never accepts IPv6-mapped IPv4 addresses.
            return false;
        }
        if (address instanceof Inet6Address) {
            if (maskBits == 0) {
                return true;
            }
            final long[] value = ipv6AddressToLongArray((Inet6Address) address);
            return (skipCompare[0] || (value[0] >= lowerBound[0] && value[0] <= upperBound[0])) &&
                   (skipCompare[1] || (value[1] >= lowerBound[1] && value[1] <= upperBound[1]));
        }
        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("baseAddress", baseAddress)
                          .add("maskBits", maskBits)
                          .add("lowerBound", "0x" + Long.toHexString(lowerBound[0]) +
                                             ':' + Long.toHexString(lowerBound[1]))
                          .add("upperBound", "0x" + Long.toHexString(upperBound[0]) +
                                             ':' + Long.toHexString(upperBound[1]))
                          .toString();
    }

    private static long[] ipv6AddressToLongArray(Inet6Address address) {
        final long[] values = new long[2];
        final byte[] bytes = address.getAddress();
        assert bytes.length == 16;
        for (int i = 0; i < bytes.length; i++) {
            final int index = i / 8;
            values[index] <<= 8;
            values[index] |= bytes[i] & 0xFF;
        }
        return values;
    }

    private static long[] calculateLowerBound(Inet6Address baseAddress, long[] mask) {
        final long[] values = ipv6AddressToLongArray(baseAddress);
        if (mask[0] > 0L) {
            // e.g. (-mask) = FFFF:FFFF:0000:0000:0000:0000:0000:0000 if maskBits 32
            //                |---- Index 0 ----| |---- Index 1 ----|
            values[0] &= -mask[0];
            values[1] = 0L;
        } else {
            // Do not update values[0] because its mask bits are filled with 1.
            // e.g. (-mask) = FFFF:FFFF:FFFF:FFFF:FFFF:0000:0000:0000 if maskBits 80
            //                |---- Index 0 ----| |---- Index 1 ----|
            values[1] &= -mask[1];
        }
        return values;
    }

    private static long[] calculateUpperBound(long[] lowerBound, long[] mask) {
        final long[] values = new long[2];
        if (mask[0] > 0L) {
            // e.g. (mask-1) = 0000:0000:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF if maskBits 32
            //                 |---- Index 0 ----| |---- Index 1 ----|
            values[0] = lowerBound[0] + mask[0] - 1L;
            values[1] = -1L;
        } else {
            // e.g. (mask-1) = 0000:0000:0000:0000:0000:FFFF:FFFF:FFFF if maskBits 80
            //                 |---- Index 0 ----| |---- Index 1 ----|
            values[0] = lowerBound[0];
            values[1] = lowerBound[1] + mask[1] - 1L;
        }
        return values;
    }

    private static long[] calculateMask(int maskBits) {
        final long[] mask = new long[2];
        final int shift = 128 - maskBits;
        if (shift < 64) {
            // e.g. mask = 0000:0000:0000:0000:0001:0000:0000:0000 if maskBits 80
            //             |---- Index 0 ----| |---- Index 1 ----|
            mask[1] = 1L << shift;
        } else {
            // e.g. mask = 0000:0001:0000:0000:0000:0000:0000:0000 if maskBits 32
            //             |---- Index 0 ----| |---- Index 1 ----|
            mask[0] = 1L << shift - 64L;
        }
        return mask;
    }
}
