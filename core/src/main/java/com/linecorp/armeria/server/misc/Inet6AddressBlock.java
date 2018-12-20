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
package com.linecorp.armeria.server.misc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

final class Inet6AddressBlock implements Predicate<InetAddress> {

    private final Inet6Address baseAddress;
    private final int maskBits;
    private final BigInteger lowerBound;
    private final BigInteger upperBound;

    Inet6AddressBlock(Inet6Address baseAddress, int maskBits) {
        this.baseAddress = requireNonNull(baseAddress, "baseAddress");
        checkArgument(maskBits >= 0 && maskBits <= 128,
                      "maskBits: %s (expected: 0-128)", maskBits);
        this.maskBits = maskBits;

        if (maskBits == 128) {
            lowerBound = upperBound = ipv6AddressToBigInteger(baseAddress);
        } else if (maskBits == 0) {
            lowerBound = upperBound = BigInteger.ZERO;
        } else {
            // Calculate the lower and upper bounds of this address block.
            // See Inet4AddressBlock if you want to know how they are calculated.
            final BigInteger mask = BigInteger.ONE.shiftLeft(128 - maskBits);
            lowerBound = ipv6AddressToBigInteger(baseAddress).and(mask.negate());
            upperBound = lowerBound.add(mask.subtract(BigInteger.ONE));
        }
    }

    @Override
    public boolean test(InetAddress address) {
        requireNonNull(address, "address");
        if (maskBits == 0) {
            return true;
        }
        if (address instanceof Inet4Address) {
            // This block never accepts IPv6-mapped IPv4 addresses.
            return false;
        }
        if (address instanceof Inet6Address) {
            try {
                final BigInteger addr = ipv6AddressToBigInteger((Inet6Address) address);
                return addr.compareTo(lowerBound) >= 0 && addr.compareTo(upperBound) <= 0;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("baseAddress", baseAddress)
                          .add("maskBits", maskBits)
                          .add("lowerBound", "0x" + lowerBound.toString(16))
                          .add("upperBound", "0x" + upperBound.toString(16))
                          .toString();
    }

    /**
     * Returns the {@link BigInteger} representation of the specified {@link InetAddress}.
     */
    private static BigInteger ipv6AddressToBigInteger(Inet6Address inetAddress) {
        final byte[] ipv6 = inetAddress.getAddress();
        return ipv6[0] == -1 ? new BigInteger(1, ipv6)
                             : new BigInteger(ipv6);
    }
}
