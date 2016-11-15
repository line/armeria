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

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

import junitextensions.OptionAssert;
import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;
import zookeeperjunit.ZooKeeperAssert;

public class ZooKeeperEndpointGroupTest implements ZooKeeperAssert, OptionAssert {
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperEndpointGroup.class);

    private static final File ROOT_DIR = new File("build" + File.separator + "zookeeper");

    static {
        ROOT_DIR.mkdirs();
    }

    private static final Duration duration = Duration.ofSeconds(5);
    private static final ZKInstance zkInstance = ZKFactory.apply().withRootDir(ROOT_DIR).create();
    private static final String zNode = "/testEndPoints";
    private static final int sessionTimeout = 3000;
    private ZooKeeperEndpointGroup zkEndpointGroup;
    private static final List<Endpoint> initializedEndpointGroupList = new ArrayList<>();
    private static final KeeperState[] expectedStates = {
            KeeperState.SyncConnected, KeeperState.Disconnected,
            KeeperState.Expired, KeeperState.SyncConnected
    };

    @Override
    public ZKInstance instance() {
        return zkInstance;
    }

    @BeforeClass
    public static void start() {
        initializedEndpointGroupList.add(Endpoint.of("127.0.0.1", 1234, 2));
        initializedEndpointGroupList.add(Endpoint.of("127.0.0.1", 2345, 4));
        initializedEndpointGroupList.add(Endpoint.of("127.0.0.1", 3456, 2));

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
            logger.warn("Failed to stop the ZooKeeper instance", e);
        }
    }

    @Before
    public void connectZk() {
        createOrUpdateZNode(endpointListToByteArray(initializedEndpointGroupList));
        //forcing a get on the Option as we should be connected at this stage
        zkEndpointGroup = new ZooKeeperEndpointGroup(
                zkInstance.connectString().get(), zNode, sessionTimeout, true);
    }

    @After
    public void disconnectZk() {
        try {
            zkEndpointGroup.close();
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testUpdateEndpointGroup() {
        List<Endpoint> expected = ImmutableList.of(Endpoint.of("127.0.0.1", 8001, 2),
                                                   Endpoint.of("127.0.0.1", 8002, 3));
        createOrUpdateZNode(endpointListToByteArray(expected));
        assertEquals(expected, zkEndpointGroup.endpoints());
    }

    @Test
    public void testGetEndpointGroup() {
        assertEquals(initializedEndpointGroupList, zkEndpointGroup.endpoints());
    }

    @Test
    public void testConnectionRecovery() throws Exception {
        ZooKeeper zkHandler1 = zkEndpointGroup.zkFuture().get();
        CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zkHandler2;

        //create a new handler with the same sessionId and password
        zkHandler2 = new ZooKeeper(zkInstance.connectString().get(), sessionTimeout, event -> {
            if (event.getState() == KeeperState.SyncConnected) {
                latch.countDown();
            }
        }, zkHandler1.getSessionId(), zkHandler1.getSessionPasswd());
        latch.await();
        //once connected, close the new handler to cause the original handler session expire
        zkHandler2.close();
        for (KeeperState state : expectedStates) {
            assertEquals(state, zkEndpointGroup.stateQueue().take());
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
