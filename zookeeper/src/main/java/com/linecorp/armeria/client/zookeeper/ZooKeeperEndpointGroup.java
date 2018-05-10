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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.internal.zookeeper.ZooKeeperDefaults;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper using {@link NodeValueCodec} and updates it when the children of the
 * zNode changes.
 */
public class ZooKeeperEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperEndpointGroup.class);

    private final NodeValueCodec nodeValueCodec;
    private final boolean internalClient;
    private final CuratorFramework client;
    private final PathChildrenCache pathChildrenCache;

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value using {@link NodeValueCodec}.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  ZooKeeper session timeout in milliseconds
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this(zkConnectionStr, zNodePath, sessionTimeout, NodeValueCodec.DEFAULT);
    }

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value using {@link NodeValueCodec}.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  ZooKeeper session timeout in milliseconds
     * @param nodeValueCodec  the {@link NodeValueCodec}
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  NodeValueCodec nodeValueCodec) {
        requireNonNull(zkConnectionStr, "zkConnectionStr");
        checkArgument(!zkConnectionStr.isEmpty(), "zkConnectionStr can't be empty");
        requireNonNull(zNodePath, "zNodePath");
        checkArgument(!zNodePath.isEmpty(), "zNodePath can't be empty");
        checkArgument(sessionTimeout > 0, "sessionTimeoutMillis: %s (expected: > 0)",
                      sessionTimeout);
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        internalClient = true;
        client = CuratorFrameworkFactory.builder()
                                        .connectString(zkConnectionStr)
                                        .retryPolicy(ZooKeeperDefaults.DEFAULT_RETRY_POLICY)
                                        .sessionTimeoutMs(sessionTimeout)
                                        .build();
        client.start();
        boolean success = false;
        try {
            pathChildrenCache = pathChildrenCache(zNodePath);
            pathChildrenCache.start();
            success = true;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (!success) {
                client.close();
            }
        }
    }

    /**
     * Create a ZooKeeper-based {@link EndpointGroup}, endpoints will be retrieved from a node's all children's
     * node value using {@link NodeValueCodec}.
     *
     * @param client          the {@link CuratorFramework} instance
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param nodeValueCodec  the {@link NodeValueCodec}
     */
    public ZooKeeperEndpointGroup(CuratorFramework client, String zNodePath, NodeValueCodec nodeValueCodec) {
        requireNonNull(zNodePath, "zNodePath");
        checkArgument(!zNodePath.isEmpty(), "zNodePath can't be empty");
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        internalClient = false;
        this.client = requireNonNull(client, "client");
        client.start();
        try {
            pathChildrenCache = pathChildrenCache(zNodePath);
            pathChildrenCache.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PathChildrenCache pathChildrenCache(String zNodePath) {
        final PathChildrenCache pathChildrenCache = new PathChildrenCache(client, zNodePath, true);
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
        return pathChildrenCache;
    }

    @Override
    public void close() {
        super.close();

        try {
            pathChildrenCache.close();
        } catch (IOException e) {
            logger.warn("Failed to close PathChildrenCache:", e);
        } finally {
            if (internalClient) {
                try {
                    client.close();
                } catch (Exception e) {
                    logger.warn("Failed to close CuratorFramework:", e);
                }
            }
        }
    }
}
