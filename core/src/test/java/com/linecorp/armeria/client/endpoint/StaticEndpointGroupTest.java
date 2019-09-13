/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class StaticEndpointGroupTest {
    @Test
    public void isStaticIPs() {
        final StaticEndpointGroup endpointGroup = new StaticEndpointGroup(ImmutableList.of(
            Endpoint.of("127.0.0.1", 3333), Endpoint.of("127.0.0.1", 1111)));

        assertThat(endpointGroup.isStaticIPs()).isTrue();
    }

    @Test
    public void isNotStaticIPs_whenUnresolved() {
        final StaticEndpointGroup endpointGroup = new StaticEndpointGroup(ImmutableList.of(
            Endpoint.of("hosta", 3333), Endpoint.of("hostb", 1111)));

        assertThat(endpointGroup.isStaticIPs()).isFalse();
    }

    @Test
    public void isNotStaticIPs_whenMixed() {
        final StaticEndpointGroup endpointGroup = new StaticEndpointGroup(ImmutableList.of(
            Endpoint.of("127.0.0.1", 3333), Endpoint.of("hostb", 1111)));

        assertThat(endpointGroup.isStaticIPs()).isFalse();
    }
}
