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

import java.util.List;
import java.util.Set;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceType;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.server.zookeeper.ZooKeeperRegistrationSpec;

import zookeeperjunit.CloseableZooKeeper;

class ZooKeeperEndpointGroupTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final String CURATOR_X_SERVICE_NAME = "foo";
    private static final int SESSION_TIMEOUT_MILLIS = 20000;

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void legacyDiscoverySpec() throws Throwable {
        final List<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints(3);
        setLegacySpecNodeChildren(sampleEndpoints);
        final ZooKeeperEndpointGroup endpointGroup = endpointGroup(ZooKeeperDiscoverySpec.legacy());
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints));

        // Add two more nodes.
        final List<Endpoint> extraEndpoints = ZooKeeperTestUtil.sampleEndpoints(2);
        setLegacySpecNodeChildren(extraEndpoints);

        // Construct the final expected node list.
        final Builder<Endpoint> builder = ImmutableSet.builder();
        builder.addAll(sampleEndpoints).addAll(extraEndpoints);
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.sync(Z_NODE, (rc, path, ctx) -> {}, null);
        }

        final Set<Endpoint> expected = builder.build();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(expected));
        disconnectZk(endpointGroup);
    }

    private static void setLegacySpecNodeChildren(List<Endpoint> children) throws Throwable {
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            // If the parent node does not exist, create it.
            if (!zk.exists(Z_NODE).get()) {
                zk.create(Z_NODE, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // Register all child nodes.
            for (Endpoint endpoint : children) {
                zk.create(Z_NODE + '/' + endpoint.host() + '_' + endpoint.port(),
                          ZooKeeperRegistrationSpec.legacy(endpoint).encodedInstance(),
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }

        for (Endpoint endpoint : children) {
            zkInstance.assertExists(Z_NODE + '/' + endpoint.host() + '_' + endpoint.port());
        }
    }

    private static ZooKeeperEndpointGroup endpointGroup(ZooKeeperDiscoverySpec spec) {
        return ZooKeeperEndpointGroup.builder(zkInstance.instance().connectString().get(), Z_NODE, spec)
                                     .sessionTimeoutMillis(SESSION_TIMEOUT_MILLIS)
                                     .build();
    }

    private static ZooKeeperEndpointGroup endpointGroup(CuratorFramework client, ZooKeeperDiscoverySpec spec) {
        return ZooKeeperEndpointGroup.builder(client, Z_NODE, spec)
                                     .build();
    }

    private static void disconnectZk(ZooKeeperEndpointGroup endpointGroup) {
        endpointGroup.close();
        // Clear the ZooKeeper nodes.
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.deleteRecursively(Z_NODE);
        }
    }

    @Test
    void curatorDiscovery() throws Throwable {
        final List<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints(3);
        setCuratorXNodeChildren(sampleEndpoints, 0);
        final ZooKeeperDiscoverySpec spec = ZooKeeperDiscoverySpec.builderForCurator(CURATOR_X_SERVICE_NAME)
                                                                  .build();
        final ZooKeeperEndpointGroup endpointGroup = endpointGroup(spec);
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints));

        // Add two more nodes.
        final List<Endpoint> extraEndpoints = ZooKeeperTestUtil.sampleEndpoints(2);
        setCuratorXNodeChildren(extraEndpoints, 3);

        // Construct the final expected node list.
        final Builder<Endpoint> builder = ImmutableSet.builder();
        builder.addAll(sampleEndpoints).addAll(extraEndpoints);
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            zk.sync(Z_NODE, (rc, path, ctx) -> {}, null);
        }

        final Set<Endpoint> expected = builder.build();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(expected));
        disconnectZk(endpointGroup);
    }

    private static void setCuratorXNodeChildren(List<Endpoint> children, int startingIdNumber)
            throws Throwable {
        final String servicePath = Z_NODE + '/' + CURATOR_X_SERVICE_NAME;
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            // If the parent node does not exist, create it.
            if (!zk.exists(Z_NODE).get()) {
                zk.create(Z_NODE, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            if (!zk.exists(servicePath).get()) {
                zk.create(servicePath, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            final ObjectMapper mapper = new ObjectMapper();
            // Register all child nodes.
            for (int i = 0; i < children.size(); i++) {
                zk.create(servicePath + '/' + (i + startingIdNumber),
                          mapper.writeValueAsBytes(serviceInstance(children.get(i), i + startingIdNumber)),
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }

        for (int i = 0; i < children.size(); i++) {
            zkInstance.assertExists(servicePath + '/' + (i + startingIdNumber));
        }
    }

    private static ServiceInstance<?> serviceInstance(Endpoint endpoint, int index) {
        return new ServiceInstance<>(CURATOR_X_SERVICE_NAME, String.valueOf(index), endpoint.host(),
                                     endpoint.port(), null, null, 0, ServiceType.DYNAMIC, null);
    }

    @Test
    void curatorDiscoverySpecWithExternalClient() throws Throwable {
        final CuratorFramework client =
                CuratorFrameworkFactory.builder()
                                       .connectString(zkInstance.connectString())
                                       .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                                       .build();
        client.start();

        final List<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints(3);
        setCuratorXNodeChildren(sampleEndpoints, 0);
        final ZooKeeperDiscoverySpec spec = ZooKeeperDiscoverySpec.builderForCurator(CURATOR_X_SERVICE_NAME)
                                                                  .build();
        final ZooKeeperEndpointGroup endpointGroup = endpointGroup(client, spec);
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints));

        disconnectZk(endpointGroup);
    }
}
