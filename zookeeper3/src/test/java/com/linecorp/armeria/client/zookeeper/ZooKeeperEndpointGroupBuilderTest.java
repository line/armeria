/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;

class ZooKeeperEndpointGroupBuilderTest {

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void selectionTimeout_default() {
        try (ZooKeeperEndpointGroup group =
                     ZooKeeperEndpointGroup.of(zkInstance.connectString(), "/node",
                                               ZooKeeperDiscoverySpec.curator("my-service"))) {
            assertThat(group.selectionTimeoutMillis()).isEqualTo(Flags.defaultResponseTimeoutMillis());
        }
    }

    @Test
    void selectionTimeout_custom() {
        try (ZooKeeperEndpointGroup group =
                     ZooKeeperEndpointGroup.builder(zkInstance.connectString(), "/node",
                                                    ZooKeeperDiscoverySpec.curator("my-service"))
                                           .selectionTimeoutMillis(3000)
                                           .build()) {
            assertThat(group.selectionTimeoutMillis()).isEqualTo(3000);
        }
    }
}
