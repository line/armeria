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
package com.linecorp.armeria.client.routing;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

import junitextensions.OptionAssert;
import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;
import zookeeperjunit.ZooKeeperAssert;

public class ZookeeperEndpointGroupTest implements ZooKeeperAssert, OptionAssert {

    private static final Duration duration = Duration.ofSeconds(5);
    private final ZKInstance zkInstance = ZKFactory.apply().create();
    private String znode = "/testEndPoints";
    ZookeeperEndpointGroup zookeeperEndpointGroup;

    @Override
    public ZKInstance instance() {
        return zkInstance;
    }

    @Before
    public void start() throws TimeoutException, Throwable {
        zkInstance.start().result(duration);
        initialZkConnection();

    }

    @After
    public void stop() throws TimeoutException, InterruptedException {
        zkInstance.destroy().ready(duration);

    }

    @Test
    public void updateEndpoints() {
        ArrayList<Endpoint> expected = new ArrayList<>();
        expected.add(Endpoint.of("127.0.0.1", 8001, 2));
        expected.add(Endpoint.of("127.0.0.1", 8002, 3));

        createOrUpdateZNode(znode, endPointListTobyteArray(expected));
        assertEquals(zookeeperEndpointGroup.endpoints(), expected);

    }

    public void initialZkConnection() {
        ArrayList<Endpoint> expected = new ArrayList<>();
        expected.add(Endpoint.of("127.0.0.1", 1234, 2));
        expected.add(Endpoint.of("127.0.0.1", 2345, 4));
        expected.add(Endpoint.of("127.0.0.1", 3456, 2));

        byte[] nodeValue = endPointListTobyteArray(expected);

        createOrUpdateZNode(znode, nodeValue);
        //forcing a get on the Option as we should be connected at this stage
        zookeeperEndpointGroup = new ZookeeperEndpointGroup(
                zkInstance.connectString().get(), znode, 3000,
                ZookeeperEndpointGroupTest::nodeValueToEndpoints);
        assertEquals(zookeeperEndpointGroup.endpoints(), expected);
    }

    public void createOrUpdateZNode(String znode, byte[] nodeValue) {
        zkInstance.connect().map(closeableZooKeeper -> {
            if (closeableZooKeeper.exists(this.znode).get()) {
                return closeableZooKeeper.setData(znode, nodeValue,
                                                  closeableZooKeeper.exists(this.znode, false).getVersion());
            }
            return closeableZooKeeper.create(znode, nodeValue, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                             CreateMode.PERSISTENT);

        });
        assertExists(this.znode);
    }

    public static List<Endpoint> nodeValueToEndpoints(byte[] nodeValue) {
        return Arrays.stream((new String(nodeValue)).split(",")).map(hostInfo -> {
            String[] token = hostInfo.split(":");
            return Endpoint.of(token[0], Integer.parseInt(token[1]), Integer.parseInt(token[2]));
        }).collect(Collectors.toList());
    }

    public static byte[] endPointListTobyteArray(List<Endpoint> endpointList) {
        return endpointList.stream().map(
                endPointA -> (endPointA.authority() + ":" + endPointA.weight())).reduce(
                (endPointAStr, endPointBStr) -> (
                        endPointAStr +
                        "," + endPointBStr)).orElse("").getBytes();

    }

}
