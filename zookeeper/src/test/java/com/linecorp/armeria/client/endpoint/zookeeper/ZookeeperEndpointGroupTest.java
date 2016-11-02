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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

import junitextensions.OptionAssert;
import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;
import zookeeperjunit.ZooKeeperAssert;

public class ZookeeperEndpointGroupTest implements ZooKeeperAssert, OptionAssert {
    private static final Duration duration = Duration.ofSeconds(5);
    private static final ZKInstance zkInstance = ZKFactory.apply().create();
    private String zNode = "/testEndPoints";
    private ZookeeperEndpointGroup zookeeperEndpointGroup;
    private static ArrayList<Endpoint> initializedEndpointGroupList = new ArrayList<>();

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
            throwable.printStackTrace();
        }
    }

    @AfterClass
    public static void stop() {
        try {
            zkInstance.stop().ready(duration);
        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Before
    public void connectZk() {
        createOrUpdateZNode(zNode, endPointListTobyteArray(initializedEndpointGroupList));
        //forcing a get on the Option as we should be connected at this stage
        zookeeperEndpointGroup = new ZookeeperEndpointGroup(
                zkInstance.connectString().get(), zNode, 3000,
                new DefaultNodeValueConverter());
    }

    @After
    public void disconnectZk() {
        try {
            zookeeperEndpointGroup.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testUpdateEndpointGroup() {
        ArrayList<Endpoint> expected = new ArrayList<>();
        expected.add(Endpoint.of("127.0.0.1", 8001, 2));
        expected.add(Endpoint.of("127.0.0.1", 8002, 3));

        createOrUpdateZNode(zNode, endPointListTobyteArray(expected));
        assertEquals(zookeeperEndpointGroup.endpoints(), expected);
    }

    @Test
    public void testGetEndpointGroup() {
        assertEquals(zookeeperEndpointGroup.endpoints(), initializedEndpointGroupList);
    }

    private void createOrUpdateZNode(String znode, byte[] nodeValue) {
        zkInstance.connect().map(closeableZooKeeper -> {
            if (closeableZooKeeper.exists(this.zNode).get()) {
                return closeableZooKeeper.setData(znode, nodeValue,
                                                  closeableZooKeeper.exists(this.zNode, false).getVersion());
            }
            return closeableZooKeeper.create(znode, nodeValue, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                             CreateMode.PERSISTENT);

        });
        assertExists(this.zNode);
    }

    private static byte[] endPointListTobyteArray(List<Endpoint> endpointList) {
        return endpointList.stream().map(
                endPointA -> (endPointA.authority() + ":" + endPointA.weight())).reduce(
                (endPointAStr, endPointBStr) -> (
                        endPointAStr +
                        "," + endPointBStr)).orElse("").getBytes();
    }
}
