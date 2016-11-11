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
package com.linecorp.armeria.client.endpoint.zookeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

import junitextensions.OptionAssert;
import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;
import zookeeperjunit.ZooKeeperAssert;

public class ZookeeperEndpointGroupTest implements ZooKeeperAssert, OptionAssert {
    private static final Duration duration = Duration.ofSeconds(5);
    private static final ZKInstance zkInstance = ZKFactory.apply().create();
    private static final String zNode = "/testEndPoints";
    private static final int sessionTimeout = 3000;
    private static final int retryWait = 5000;
    private ZookeeperEndpointGroup zookeeperEndpointGroup;
    private static final List<Endpoint> initializedEndpointGroupList = new ArrayList<>();
    private static final Stack<KeeperState> expectedStates = new Stack<>();

    @Override
    public ZKInstance instance() {
        return zkInstance;
    }

    @BeforeClass
    public static void start() {
        initializedEndpointGroupList.add(Endpoint.of("127.0.0.1", 1234, 2));
        initializedEndpointGroupList.add(Endpoint.of("127.0.0.1", 2345, 4));
        initializedEndpointGroupList.add(Endpoint.of("127.0.0.1", 3456, 2));

        expectedStates.push(KeeperState.SyncConnected);
        expectedStates.push(KeeperState.Disconnected);
        expectedStates.push(KeeperState.Expired);
        expectedStates.push(KeeperState.SyncConnected);

        try {
            zkInstance.start().result(duration);
        } catch (Throwable throwable) {
            fail();
        }
    }

    @AfterClass
    public static void stop() {
        try {
            zkInstance.stop().ready(duration);
        } catch (Exception e) {
            //ignore
        }
    }

    @Before
    public void connectZk() {
        createOrUpdateZNode(endpointListToByteArray(initializedEndpointGroupList));
        //forcing a get on the Option as we should be connected at this stage
        zookeeperEndpointGroup = new ZookeeperEndpointGroup(
                zkInstance.connectString().get(), zNode, sessionTimeout, true);
    }

    @After
    public void disconnectZk() {
        try {
            zookeeperEndpointGroup.close();
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testUpdateEndpointGroup() {
        List<Endpoint> expected = ImmutableList.of(Endpoint.of("127.0.0.1", 8001, 2),
                                                   Endpoint.of("127.0.0.1", 8002, 3));
        createOrUpdateZNode(endpointListToByteArray(expected));
        assertEquals(expected, zookeeperEndpointGroup.endpoints());
    }

    @Test
    public void testGetEndpointGroup() {
        assertEquals(initializedEndpointGroupList, zookeeperEndpointGroup.endpoints());
    }

    @Test
    public void testConnectionRecovery() {
        ZooKeeper zkHandler1 = zookeeperEndpointGroup.getZkHandler();
        CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zkHandler2;
        try {
            //create a new handler with the same sessionId and password
            zkHandler2 = new ZooKeeper(zkInstance.connectString().get(), sessionTimeout, event -> {
                if (event.getState() == KeeperState.SyncConnected) {
                    latch.countDown();
                }
            }, zkHandler1.getSessionId(), zkHandler1.getSessionPasswd());
            latch.await();
            //once connected, close the new handler to cause the original handler session expire
            zkHandler2.close();
            //wait  for the session to be expired
            Thread.sleep(retryWait);
            assertEquals(expectedStates, zookeeperEndpointGroup.getStatesStack());
        } catch (Exception e) {
            fail();
        }

    }

    private void createOrUpdateZNode(byte[] nodeValue) {
        zkInstance.connect().map(closeableZooKeeper -> {
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

    private static byte[] endpointListToByteArray(List<Endpoint> endpointList) {
        return endpointList.stream()
                           .map(endPointA -> endPointA.authority() + ':' + endPointA.weight())
                           .reduce((endPointAStr, endPointBStr) -> endPointAStr + ',' + endPointBStr)
                           .orElse("").getBytes(Charsets.UTF_8);
    }
}
