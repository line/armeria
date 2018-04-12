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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;

import zookeeperjunit.CloseableZooKeeper;

public class ZooKeeperEndpointGroupTest extends ZooKeeperTestBase {

    @Nullable
    private ZooKeeperEndpointGroup endpointGroup;

    @Before
    public void connectZk() throws Throwable {
        // Create the endpoint group and initialize the ZooKeeper nodes.
        setNodeChild(sampleEndpoints);
        endpointGroup = new ZooKeeperEndpointGroup(
                instance().connectString().get(), zNode, sessionTimeoutMillis);
    }

    @After
    public void disconnectZk() {
        if (endpointGroup != null) {
            endpointGroup.close();
            endpointGroup = null;
        }

        // Clear the ZooKeeper nodes.
        try (CloseableZooKeeper zk = connection()) {
            zk.deleteRecursively(zNode);
        }
    }

    @Test
    public void testGetEndpointGroup() {
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints));
    }

    @Test
    public void testUpdateEndpointGroup() throws Throwable {
        Set<Endpoint> expected = ImmutableSet.of(Endpoint.of("127.0.0.1", 8001).withWeight(2),
                                                 Endpoint.of("127.0.0.1", 8002).withWeight(3));
        // Add two more nodes.
        setNodeChild(expected);
        // Construct the final expected node list.
        final Builder<Endpoint> builder = ImmutableSet.builder();
        builder.addAll(sampleEndpoints).addAll(expected);
        expected = builder.build();

        try (CloseableZooKeeper zk = connection()) {
            zk.sync(zNode, (rc, path, ctx) -> {}, null);
        }

        final Set<Endpoint> finalExpected = expected;
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameElementsAs(finalExpected));
    }

    private void setNodeChild(Set<Endpoint> children) throws Throwable {
        try (CloseableZooKeeper zk = connection()) {
            // If the parent node does not exist, create it.
            if (!zk.exists(zNode).get()) {
                zk.create(zNode, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // Register all child nodes.
            children.forEach(endpoint -> {
                try {
                    zk.create(zNode + '/' + endpoint.host() + '_' + endpoint.port(),
                              NodeValueCodec.DEFAULT.encode(endpoint),
                              Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }
        children.forEach(endpoint -> assertExists(zNode + '/' + endpoint.host() + '_' + endpoint.port()));
    }
}
