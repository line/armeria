/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.zookeeper;

import static com.linecorp.armeria.client.zookeeper.StoreType.IN_CHILD_NODES;
import static com.linecorp.armeria.client.zookeeper.StoreType.IN_NODE_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZooKeeperException;

import junitextensions.OptionAssert;
import zookeeperjunit.ZooKeeperAssert;

@RunWith(Parameterized.class)
public class EndpointGroupTest extends TestBase implements ZooKeeperAssert, OptionAssert {
    protected static final KeeperState[] expectedStates = {
            KeeperState.Disconnected, KeeperState.Expired,
            KeeperState.SyncConnected, KeeperState.Disconnected
    };
    @Parameter
    @SuppressWarnings("VisibilityModifier")
    public StoreType storeType;
    private ZooKeeperEndpointGroup endpointGroup;

    @Parameters
    public static Collection<StoreType> endpointGroups() {
        return Collections.unmodifiableSet(EnumSet.of(IN_CHILD_NODES, IN_NODE_VALUE));
    }

    @Before
    public void connectZk() {
        //crate endpoint group and initialize node value
        switch (storeType) {
            case IN_NODE_VALUE:
                setNodeValue(NodeValueCodec.DEFAULT.encodeAll(sampleEndpoints));
                break;
            case IN_CHILD_NODES:
                setNodeChild(sampleEndpoints);
                break;
        }
        try {
            endpointGroup = new ZooKeeperEndpointGroup(
                    instance().connectString().get(), zNode, sessionTimeout, storeType);
        } catch (ZooKeeperException e) {
            fail();
        }
        //enable state recording
        endpointGroup.enableStateRecording();
    }

    @After
    public void disconnectZk() {
        try {
            endpointGroup.close();
            //clear node data
            instance().connect().forEach(zooKeeper -> zooKeeper.deleteRecursively(zNode));
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testGetEndpointGroup() {
        assertThat(endpointGroup.endpoints()).hasSameElementsAs(sampleEndpoints);
    }

    @Test
    public void testUpdateEndpointGroup() {
        Set<Endpoint> expected = ImmutableSet.of(Endpoint.of("127.0.0.1", 8001, 2),
                                                 Endpoint.of("127.0.0.1", 8002, 3));
        switch (storeType) {
            case IN_NODE_VALUE:
                setNodeValue(NodeValueCodec.DEFAULT.encodeAll(expected));
                break;
            case IN_CHILD_NODES:
                //add two more node
                setNodeChild(expected);
                //construct the final expected node list
                Builder<Endpoint> builder = ImmutableSet.builder();
                builder.addAll(sampleEndpoints).addAll(expected);
                expected = builder.build();
                break;
        }
        assertThat(endpointGroup.endpoints()).hasSameElementsAs(expected);
    }

    @Test
    public void testConnectionRecovery() throws Exception {
        ZooKeeper zkHandler1 = endpointGroup.underlyingClient();
        CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zkHandler2;
        //create a new handler with the same sessionId and password
        zkHandler2 = new ZooKeeper(instance().connectString().get(), sessionTimeout, event -> {
            if (event.getState() == KeeperState.SyncConnected) {
                latch.countDown();
            }
        }, zkHandler1.getSessionId(), zkHandler1.getSessionPasswd());
        latch.await();
        //once connected, close the new handler to cause the original handler session expire
        zkHandler2.close();
        for (KeeperState state : expectedStates) {
            assertEquals(state, endpointGroup.stateQueue().take());
        }
        testGetEndpointGroup();
    }

    private void setNodeValue(byte[] nodeValue) {
        instance().connect().map(closeableZooKeeper -> {
            if (closeableZooKeeper.exists(zNode).get()) {
                return closeableZooKeeper.setData(zNode, nodeValue,
                                                  closeableZooKeeper.exists(zNode,
                                                                            false).getVersion());
            }
            return closeableZooKeeper.create(zNode, nodeValue, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                             CreateMode.PERSISTENT);
        });
        assertExists(zNode);
    }

    private void setNodeChild(Set<Endpoint> children) {
        instance().connect().forEach(
                closeableZooKeeper -> {
                    //if the parent node dose not exist, create it
                    try {
                        if (!closeableZooKeeper.exists(zNode).get()) {
                            closeableZooKeeper
                                    .create(zNode, null, Ids.OPEN_ACL_UNSAFE,
                                            CreateMode.PERSISTENT);
                        }
                    } catch (Throwable throwable) {
                        fail();
                    }
                    //register all child node
                    children.forEach(endpoint -> {
                        try {
                            closeableZooKeeper
                                    .create(zNode + '/' + endpoint.host() + '_' + endpoint.port(),
                                            NodeValueCodec.DEFAULT.encode(endpoint),
                                            Ids.OPEN_ACL_UNSAFE,
                                            CreateMode.EPHEMERAL);
                        } catch (Exception e) {
                            fail();
                            logger.error("failed to create node children", e.getMessage());
                        }
                    });
                }
        );
        children.forEach(endpoint -> assertExists(zNode + '/' + endpoint.host() + '_' + endpoint.port()));
    }
}
