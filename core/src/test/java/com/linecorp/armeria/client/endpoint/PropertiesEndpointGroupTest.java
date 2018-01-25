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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class PropertiesEndpointGroupTest {
    @Test
    public void test() {
        PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                this.getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 80);
        PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(
                this.getClass().getClassLoader(), "server-list.properties", "serverB.hosts", 8080);

        assertThat(endpointGroupA.endpoints()).containsOnly(Endpoint.of("127.0.0.1:8080"),
                                                            Endpoint.of("127.0.0.1:8081"),
                                                            Endpoint.of("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsOnly(Endpoint.of("127.0.0.1:8082"),
                                                            Endpoint.of("127.0.0.1:8083"));
        assertThatThrownBy(() -> PropertiesEndpointGroup.of(
                this.getClass().getClassLoader(), "server-list.properties", "serverC.hosts", 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains no hosts");
    }
}
