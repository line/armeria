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

import static com.linecorp.armeria.server.misc.InetAddressPredicates.ofCidr;
import static com.linecorp.armeria.server.misc.InetAddressPredicates.ofExact;
import static com.linecorp.armeria.server.misc.InetAddressPredicates.toMaskBits;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Predicate;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class InetAddressPredicatesTest {

    @Test
    public void exact() throws UnknownHostException {
        final List<Predicate<InetAddress>> filters = ImmutableList.of(
                ofExact(InetAddress.getByName("10.0.0.1")),
                ofExact("10.0.0.1"));
        for (final Predicate<InetAddress> filter : filters) {
            assertThat(filter.test(InetAddress.getByName("10.0.0.1"))).isTrue();
            assertThat(filter.test(InetAddress.getByName("10.0.0.2"))).isFalse();
        }
    }

    @Test
    public void inet4Cidr() throws UnknownHostException {
        Predicate<InetAddress> filter;

        filter = ofCidr("10.1.1.0/8");
        assertThat(filter.test(InetAddress.getByName("10.0.0.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("11.1.1.1"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("255.255.255.255"))).isFalse();

        filter = ofCidr("10.1.1.0/16");
        assertThat(filter.test(InetAddress.getByName("10.1.0.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.255.255"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.2.1.1"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("10.255.255.255"))).isFalse();

        filter = ofCidr("10.1.1.0/24");
        assertThat(filter.test(InetAddress.getByName("10.1.1.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.1.255"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.2.1"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("10.1.255.255"))).isFalse();

        filter = ofCidr("10.1.1.0/26");
        assertThat(filter.test(InetAddress.getByName("10.1.1.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.1.63"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.1.64"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("10.1.1.255"))).isFalse();
    }

    @Test
    public void inet4Cidr_withSubnetAddress() throws UnknownHostException {
        Predicate<InetAddress> filter;

        filter = ofCidr("10.1.1.0/255.0.0.0");
        assertThat(filter.test(InetAddress.getByName("10.0.0.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("11.1.1.1"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("255.255.255.255"))).isFalse();

        filter = ofCidr("10.1.1.0/255.255.0.0");
        assertThat(filter.test(InetAddress.getByName("10.1.0.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.255.255"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.2.1.1"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("10.255.255.255"))).isFalse();

        filter = ofCidr("10.1.1.0/255.255.255.0");
        assertThat(filter.test(InetAddress.getByName("10.1.1.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.1.255"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.2.1"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("10.1.255.255"))).isFalse();

        filter = ofCidr("10.1.1.0/255.255.255.192");
        assertThat(filter.test(InetAddress.getByName("10.1.1.0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.1.63"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10.1.1.64"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("10.1.1.255"))).isFalse();
    }

    @Test
    public void inet6Cidr() throws UnknownHostException {
        Predicate<InetAddress> filter;

        filter = ofCidr("1080:0:0:0:8:800:200C:4100/120");
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:800:200C:4100"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:800:200C:41FF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:800:200C:4200"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:800:200C:FFFF"))).isFalse();

        filter = ofCidr("1080:0:0:0:8:800:200C:4100/96");
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:800:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:800:FFFF:FFFF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:801:0:0"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:8:FFFF:FFFF:FFFF"))).isFalse();

        filter = ofCidr("1080:0:0:0:0:0:0:0/64");
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:0:0:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:0:FFFF:FFFF:FFFF:FFFF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:1:0:0:0:0"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1080:0:0:FFFF:FFFF:FFFF:FFFF:FFFF"))).isFalse();

        filter = ofCidr("1000:0:0:0:0:0:0:0/8");
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("10FF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1100:0:0:0:0:0:0:0"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF"))).isFalse();
    }

    @Test
    public void inet6Cidr_withSubnetAddress() throws UnknownHostException {
        Predicate<InetAddress> filter;

        filter = ofCidr("1000:0:0:0:0:0:0:0/255.0.0.0");
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0FF:FFFF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:FF00:0"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:FFFF:FFFF"))).isFalse();

        filter = ofCidr("1000:0:0:0:0:0:0:0/255.255.0.0");
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:FFFF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:001:0"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:00FF:FFFF"))).isFalse();

        filter = ofCidr("1000:0:0:0:0:0:0:0/255.255.255.0");
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:0FF"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:1FF"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:FFFF"))).isFalse();

        filter = ofCidr("1000:0:0:0:0:0:0:0/255.255.255.192");
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:0"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:03F"))).isTrue();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:040"))).isFalse();
        assertThat(filter.test(InetAddress.getByName("1000:0:0:0:0:0:0:FFFF"))).isFalse();
    }

    @Test
    public void subnetMaskToBits() {
        assertThat(toMaskBits("255.0.0.0")).isEqualTo(8);
        assertThat(toMaskBits("255.255.0.0")).isEqualTo(16);
        assertThat(toMaskBits("255.255.255.0")).isEqualTo(24);
        assertThat(toMaskBits("255.255.255.192")).isEqualTo(26);
        assertThat(toMaskBits("255.255.255.255")).isEqualTo(32);

        assertThatThrownBy(() -> toMaskBits("255.255.0.192"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toMaskBits("255.193.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toMaskBits("255.192.192.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
