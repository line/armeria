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
package com.linecorp.armeria.client.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.zookeeper.ZooKeeperRegistrationSpec;

import zookeeperjunit.CloseableZooKeeper;

@GenerateNativeImageTrace
class ServerSetDiscoveryTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final int SESSION_TIMEOUT_MILLIS = 20000;

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void serverSetDiscoveryUpdatingListener() throws Throwable {
        final List<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints(3);
        setServerSetNodeChildren(sampleEndpoints, 0);
        final ZooKeeperDiscoverySpec spec = ZooKeeperDiscoverySpec.serverSets();

        final ZooKeeperEndpointGroup endpointGroup = endpointGroup(spec);
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints));

        // Add two more nodes.
        final List<Endpoint> extraEndpoints = ZooKeeperTestUtil.sampleEndpoints(2);
        setServerSetNodeChildren(extraEndpoints, 3);

        // Construct the final expected node list.
        final ImmutableSet.Builder<Endpoint> builder = ImmutableSet.builder();
        builder.addAll(sampleEndpoints).addAll(extraEndpoints);
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.sync(Z_NODE, (rc, path, ctx) -> {}, null);
        }

        final Set<Endpoint> expected = builder.build();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(expected));
        disconnectZk(endpointGroup);
    }

    private static void disconnectZk(ZooKeeperEndpointGroup endpointGroup) {
        endpointGroup.close();
        // Clear the ZooKeeper nodes.
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.deleteRecursively(Z_NODE);
        }
    }

    private static ZooKeeperEndpointGroup endpointGroup(ZooKeeperDiscoverySpec spec) {
        return ZooKeeperEndpointGroup.builder(zkInstance.instance().connectString().get(), Z_NODE, spec)
                                     .sessionTimeoutMillis(SESSION_TIMEOUT_MILLIS)
                                     .build();
    }

    private static void setServerSetNodeChildren(
            List<Endpoint> children, int startingIdNumber) throws Throwable {
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            // If the parent node does not exist, create it.
            if (!zk.exists(Z_NODE).get()) {
                zk.create(Z_NODE, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            // Register all child nodes.
            for (int i = 0; i < children.size(); i++) {
                zk.create(Z_NODE + "/member_", ZooKeeperRegistrationSpec.builderForServerSets()
                                                                        .serviceEndpoint(children.get(i))
                                                                        .build()
                                                                        .encodedInstance(),
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
        }

        for (int i = 0; i < children.size(); i++) {
            zkInstance.assertExists(Z_NODE + "/member_000000000" + (i + startingIdNumber));
        }
    }
}
