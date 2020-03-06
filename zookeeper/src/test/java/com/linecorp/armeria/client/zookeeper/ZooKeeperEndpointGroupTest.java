/*
 * Copyright 2016 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.Set;

import javax.annotation.Nullable;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;

import zookeeperjunit.CloseableZooKeeper;

class ZooKeeperEndpointGroupTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final int SESSION_TIMEOUT_MILLIS = 20000;
    private static final Set<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints();

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();
    @Nullable
    private static ZooKeeperEndpointGroup endpointGroup;

    private static void setNodeChild(Set<Endpoint> children) throws Throwable {
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            // If the parent node does not exist, create it.
            if (!zk.exists(Z_NODE).get()) {
                zk.create(Z_NODE, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // Register all child nodes.
            for (Endpoint endpoint : children) {
                zk.create(Z_NODE + '/' + endpoint.host() + '_' + endpoint.port(),
                          NodeValueCodec.ofDefault().encode(endpoint),
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }

        for (Endpoint endpoint : children) {
            zkInstance.assertExists(Z_NODE + '/' + endpoint.host() + '_' + endpoint.port());
        }
    }

    @BeforeEach
    void connectZk() throws Throwable {
        // Create the endpoint group and initialize the ZooKeeper nodes.
        setNodeChild(sampleEndpoints);
        endpointGroup = ZooKeeperEndpointGroup.builder(zkInstance.instance().connectString().get(), Z_NODE)
                                              .sessionTimeoutMillis(SESSION_TIMEOUT_MILLIS)
                                              .build();
    }

    @AfterEach
    void disconnectZk() {
        if (endpointGroup != null) {
            endpointGroup.close();
            endpointGroup = null;
        }

        // Clear the ZooKeeper nodes.
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.deleteRecursively(Z_NODE);
        }
    }

    @Test
    void testGetEndpointGroup() {
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints));
    }

    @Test
    void testUpdateEndpointGroup() throws Throwable {
        Set<Endpoint> expected = ImmutableSet.of(Endpoint.of("127.0.0.1", 8001).withWeight(2),
                                                 Endpoint.of("127.0.0.1", 8002).withWeight(3));
        // Add two more nodes.
        setNodeChild(expected);
        // Construct the final expected node list.
        final Builder<Endpoint> builder = ImmutableSet.builder();
        builder.addAll(sampleEndpoints).addAll(expected);
        expected = builder.build();

        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.sync(Z_NODE, (rc, path, ctx) -> {}, null);
        }

        final Set<Endpoint> finalExpected = expected;
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(finalExpected));
    }
}
