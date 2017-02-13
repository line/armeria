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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZooKeeperConnector;
import com.linecorp.armeria.common.zookeeper.ZooKeeperException;
import com.linecorp.armeria.common.zookeeper.ZooKeeperListener;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper depending on {@link StoreType} and updates it when the children of the
 * zNode changes.
 */
public class ZooKeeperEndpointGroup implements EndpointGroup {
    private final NodeValueCodec nodeValueCodec;
    private final ZooKeeperConnector zooKeeperConnector;
    private List<Endpoint> prevData;

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value or a node's value depending on value store type {@link StoreType}
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param storeType       where data was stored, as a node value or as a node's all children
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  StoreType storeType) {
        this(zkConnectionStr, zNodePath, sessionTimeout, NodeValueCodec.DEFAULT, storeType);
    }

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value or a node's value depending on value store type {@link StoreType}
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param nodeValueCodec  the nodeValueCodec
     * @param storeType       where data was stored, as a node value or as a node's all children
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  NodeValueCodec nodeValueCodec, StoreType storeType) {
        requireNonNull(storeType, "storeType");
        zooKeeperConnector = new ZooKeeperConnector(zkConnectionStr, zNodePath, sessionTimeout,
                                                    createListener(storeType));
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        zooKeeperConnector.connect();
    }

    @Override
    public List<Endpoint> endpoints() {
        return prevData;
    }

    @Override
    public void close() {
        zooKeeperConnector.close(true);
    }

    /**
     * Create a {@link ZooKeeperListener} listens specific ZooKeeper events.
     * @param storeType            storeType
     * @return                     {@link ZooKeeperListener}
     */
    private ZooKeeperListener createListener(StoreType storeType) {
        switch (storeType) {
            case IN_CHILD_NODES:
                return new ZooKeeperListener() {
                    @Override
                    public void nodeChildChange(Map<String, String> newChildrenValue) {
                        List<Endpoint> newData = newChildrenValue.values().stream().map(
                                nodeValueCodec::decode).filter(Objects::nonNull).collect(Collectors.toList());
                        if (prevData == null || !prevData.equals(newData)) {
                            prevData = newData;
                        }
                    }

                    @Override
                    public void nodeValueChange(String newValue) {
                        //ignore value change event
                    }

                    @Override
                    public void connected() {
                    }
                };
            case IN_NODE_VALUE:
                return new ZooKeeperListener() {
                    @Override
                    public void nodeChildChange(Map<String, String> newChildrenValue) {
                    }

                    @Override
                    public void nodeValueChange(String newValue) {
                        List<Endpoint> newData = nodeValueCodec.decodeAll(newValue).stream().filter(
                                Objects::nonNull).collect(Collectors.toList());
                        if (prevData == null || !prevData.equals(newData)) {
                            prevData = newData;
                        }
                    }

                    @Override
                    public void connected() {
                    }
                };
            default:
                throw new ZooKeeperException("unknown listener type: " + storeType);
        }
    }

    @VisibleForTesting
    void enableStateRecording() {
        zooKeeperConnector.enableStateRecording();
    }

    @VisibleForTesting
    ZooKeeper underlyingClient() {
        return zooKeeperConnector.underlyingClient();
    }

    @VisibleForTesting
    BlockingQueue<KeeperState> stateQueue() {
        return zooKeeperConnector.stateQueue();
    }
}
