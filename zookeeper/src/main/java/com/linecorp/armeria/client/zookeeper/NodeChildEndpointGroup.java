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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.zookeeper.ZooKeeper;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.zookeeper.DefaultNodeValueCodec;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZKConnector;
import com.linecorp.armeria.common.zookeeper.ZooKeeperException;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper zNode's all children node's value and updates it when the children of the
 * zNode changes. When a ZooKeeper session expires, it will automatically reconnect to the ZooKeeper with
 * exponential retry delay,starting from 1 second up to 60 seconds.
 */
public class NodeChildEndpointGroup extends ZKConnector implements EndpointGroup {
    private final NodeValueCodec nodeValueCodec;
    private List<Endpoint> prevData;

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     */
    public NodeChildEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this(zkConnectionStr, zNodePath, sessionTimeout, new DefaultNodeValueCodec());
    }

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param nodeValueCodec           the nodeValueCodec
     */
    public NodeChildEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  NodeValueCodec nodeValueCodec) {
        super(zkConnectionStr, zNodePath, sessionTimeout);
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        connect();
    }

    @Override
    protected void postConnected(ZooKeeper zooKeeper) {
        prevData = doGetEndpoints(zooKeeper, getzNodePath());
    }

    /**
     * Get endpoints.
     * @param zooKeeper ZooKeeper client
     * @param zNodePath ZooKeeper node path
     */
    private List<Endpoint> doGetEndpoints(ZooKeeper zooKeeper, String zNodePath) {
        //wait if node has not been create by server
        try {
            while (zooKeeper.exists(zNodePath, false) == null) {
                doWait();
            }
            resetRetryDelay();
            List<String> children;
            while ((children = zooKeeper.getChildren(zNodePath, true)) == null) {
                doWait();
            }
            return children.stream().map(
                    child -> {
                        try {
                            return nodeValueCodec.decode(
                                    zooKeeper.getData(zNodePath + '/' + child, false, null));
                        } catch (Exception e) {
                            throw new ZooKeeperException(e);
                        }
                    }
            ).filter(Objects::nonNull).collect(Collectors.toList());
        } catch (Exception e) {
            throw new ZooKeeperException(e);
        }
    }

    @Override
    public void close() {
        close(true);
    }

    @Override
    protected void nodeChange(ZooKeeper zooKeeper, String zNodePath) {
        List<Endpoint> newData = doGetEndpoints(zooKeeper, zNodePath);
        if (!Arrays.equals(prevData.toArray(), newData.toArray())) {
            prevData = newData;
        }
    }

    @Override
    public List<Endpoint> endpoints() {
        return prevData;
    }
}
