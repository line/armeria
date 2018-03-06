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

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.retry.ExponentialBackoffRetry;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper using {@link NodeValueCodec} and updates it when the children of the
 * zNode changes.
 */
public class ZooKeeperEndpointGroup extends DynamicEndpointGroup {
    private final NodeValueCodec nodeValueCodec;
    private final PathChildrenCache pathChildrenCache;

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value using {@link NodeValueCodec}.
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this(zkConnectionStr, zNodePath, sessionTimeout, NodeValueCodec.DEFAULT);
    }

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value using {@link NodeValueCodec}.
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param nodeValueCodec  the nodeValueCodec
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  NodeValueCodec nodeValueCodec) {
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework client = CuratorFrameworkFactory.builder()
                                                         .connectString(zkConnectionStr)
                                                         .retryPolicy(retryPolicy)
                                                         .sessionTimeoutMs(sessionTimeout)
                                                         .build();
        client.start();
        pathChildrenCache = new PathChildrenCache(client, zNodePath, true);
        pathChildrenCache.getListenable().addListener((c, event) -> {
            switch (event.getType()) {
                case CHILD_ADDED:
                    addEndpoint(nodeValueCodec.decode(event.getData().getData()));
                    break;
                case CHILD_REMOVED:
                    removeEndpoint(nodeValueCodec.decode(event.getData().getData()));
                    break;
                default:
                    break;
            }
        });
        try {
            pathChildrenCache.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            pathChildrenCache.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
