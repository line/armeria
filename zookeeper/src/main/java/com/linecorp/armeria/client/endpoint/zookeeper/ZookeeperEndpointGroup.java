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

import static java.util.Objects.requireNonNull;
import static org.apache.zookeeper.KeeperException.Code.get;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.routing.EndpointGroup;
import com.linecorp.armeria.client.routing.EndpointGroupException;

/**
 *  A Zookeeper based {@link EndpointGroup} implementation.
 *  It will get the {@link EndpointGroup} information from a Zookeeper zNode, any update to it will
 *  be reflected asynchronously to the group.
 */
public class ZookeeperEndpointGroup implements EndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperEndpointGroup.class);

    private final String zkConnectionStr;
    private final String znode;
    private List<Endpoint> endpointList;
    private ZooKeeper zooKeeper;
    private byte[] prevData;
    private final NodeValueConverter nodeValueToEndpointList;

    private final CountDownLatch connectionLatch = new CountDownLatch(1);

    /**
     * Creates a {@link ZookeeperEndpointGroup}.
     * @param zkConnectionStr A connection string containing a comma
     *          separated list of host:port pairs,each corresponding to a ZooKeeper server
     * @param zNode a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout   Zookeeper session timeout in milliseconds
     * @param converter a function to convert zNode value to a List of {@link Endpoint}
     */
    public ZookeeperEndpointGroup(String zkConnectionStr, String zNode, int sessionTimeout,
                                  NodeValueConverter converter) {

        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.znode = requireNonNull(zNode, "zNode");
        this.nodeValueToEndpointList = requireNonNull(converter, "nodeValueToEndpointList");
        try {
            zooKeeper = new ZooKeeper(zkConnectionStr, sessionTimeout, event -> {
                if (event.getState() == KeeperState.SyncConnected) {
                    connectionLatch.countDown();
                }

            });
            connectionLatch.await();
            Stat stat = zooKeeper.exists(zNode, new ZkWatcher());
            byte[] nodeData;
            if (stat != null) {
                nodeData = zooKeeper.getData(zNode, false, null);
                endpointList = nodeValueToEndpointList.convert(nodeData);
            }

        } catch (Exception e) {
            throw new EndpointGroupException("failed to connect to ZooKeeper:" + zkConnectionStr);
        }
    }

    /**
     * Create a Zookeeper endpoint group with a {@link DefaultNodeValueConverter}.
     * @param zkConnectionStr  A connection string containing a comma separated list
     *        of host:port pairs,each corresponding to a ZooKeeper server
     * @param zNode a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout   Zookeeper session timeout in milliseconds
     */
    public ZookeeperEndpointGroup(String zkConnectionStr, String zNode, int sessionTimeout) {
        this(zkConnectionStr, zNode, sessionTimeout, new DefaultNodeValueConverter());
    }

    @Override
    public List<Endpoint> endpoints() {
        return this.endpointList;

    }

    public class ZkWatcher implements Watcher, StatCallback {
        @Override
        public void process(WatchedEvent event) {
            String path = event.getPath();
            if (event.getType() != Event.EventType.None) {
                if (path != null && path.equals(znode)) {
                    // Something has changed on the node, let's find out
                    zooKeeper.exists(znode, true, this, null);
                }
            }

        }

        @Override
        public void processResult(int responseCodeInt, String path, Object ctx, Stat stat) {
            boolean exists;
            Code responseCode = get(responseCodeInt);
            switch (responseCode) {
                case OK:
                    exists = true;
                    break;
                case NONODE:
                    exists = false;
                    break;
                case SESSIONEXPIRED:
                case NOAUTH:
                    return;
                default:
                    // Retry errors
                    zooKeeper.exists(znode, true, this, null);
                    return;
            }

            byte[] nodeData = null;
            if (exists) {
                try {
                    nodeData = zooKeeper.getData(znode, false, null);
                } catch (KeeperException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    return;
                }
            }
            if ((nodeData == null && nodeData != prevData) ||
                (nodeData != null && !Arrays.equals(prevData, nodeData))) {
                prevData = nodeData;
                ZookeeperEndpointGroup.this.endpointList = nodeValueToEndpointList.convert(prevData);
            }
        }
    }

    /**
     *  Close the under Zookeeper connection.
     * @throws Exception Closing exception
     */
    public void close() throws Exception {
        zooKeeper.close();

    }

    @Override
    public String toString() {
        return Joiner.on(";").join(endpoints()) + " with Zookeeper connection string:" + zkConnectionStr;
    }
}
