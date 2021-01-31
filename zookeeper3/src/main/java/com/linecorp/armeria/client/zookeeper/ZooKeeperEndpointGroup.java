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

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.server.zookeeper.ZooKeeperUpdatingListener;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper and updates it when the children of the znode changes.
 *
 * @see ZooKeeperUpdatingListener
 */
public final class ZooKeeperEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperEndpointGroup.class);

    private static final ThreadFactory closeCuratorFrameworkThreadFactory =
            new DefaultThreadFactory("armeria-close-CuratorFramework");

    /**
     * Returns a new {@link ZooKeeperEndpointGroup} that retrieves the {@link Endpoint} list from
     * the ZNode at the specified connection string and path. A new ZooKeeper client will be created internally.
     * The ZooKeeper client will be destroyed when the returned {@link ZooKeeperEndpointGroup} is closed.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperDiscoverySpec} to find and decode the registered instances
     */
    public static ZooKeeperEndpointGroup of(String zkConnectionStr, String znodePath,
                                            ZooKeeperDiscoverySpec spec) {
        return builder(zkConnectionStr, znodePath, spec).build();
    }

    /**
     * Returns a new {@link ZooKeeperEndpointGroup} that retrieves the {@link Endpoint} list from
     * the ZNode at the specified path using the specified {@link CuratorFramework}.
     * Note that the specified {@link CuratorFramework} will not be destroyed when the returned
     * {@link ZooKeeperEndpointGroup} is closed.
     *
     * @param client the curator framework instance
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperDiscoverySpec} to find and decode the registered instances
     */
    public static ZooKeeperEndpointGroup of(CuratorFramework client, String znodePath,
                                            ZooKeeperDiscoverySpec spec) {
        return builder(client, znodePath, spec).build();
    }

    /**
     * Returns a new {@link ZooKeeperEndpointGroupBuilder} created with the specified ZooKeeper connection
     * string and ZNode path. The {@link ZooKeeperEndpointGroup} built by the returned builder will create
     * a new ZooKeeper client internally. The ZooKeeper client will be destroyed when
     * the {@link ZooKeeperEndpointGroup} is closed.
     *
     * @param zkConnectionStr the ZooKeeper connection string
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperDiscoverySpec} to find and decode the registered instances
     */
    public static ZooKeeperEndpointGroupBuilder builder(
            String zkConnectionStr, String znodePath, ZooKeeperDiscoverySpec spec) {
        return new ZooKeeperEndpointGroupBuilder(zkConnectionStr, znodePath, spec);
    }

    /**
     * Returns a new {@link ZooKeeperEndpointGroupBuilder} created with the specified {@link CuratorFramework}
     * and ZNode path. Note that the specified {@link CuratorFramework} will not be destroyed when
     * the {@link ZooKeeperEndpointGroup} built by the returned builder is closed.
     *
     * @param client the curator framework instance
     * @param znodePath the ZooKeeper node to register
     * @param spec the {@link ZooKeeperDiscoverySpec} to find and decode the registered instances
     */
    public static ZooKeeperEndpointGroupBuilder builder(
            CuratorFramework client, String znodePath, ZooKeeperDiscoverySpec spec) {
        return new ZooKeeperEndpointGroupBuilder(client, znodePath, spec);
    }

    private final boolean internalClient;
    private final CuratorFramework client;
    private final PathChildrenCache pathChildrenCache;

    ZooKeeperEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                           CuratorFramework client, String znodePath,
                           ZooKeeperDiscoverySpec discoverySpec, boolean internalClient) {
        super(selectionStrategy);
        this.internalClient = internalClient;
        this.client = requireNonNull(client, "client");

        if (client.getState() != CuratorFrameworkState.STARTED) {
            client.start();
        }

        final String pathForDiscovery = discoverySpec.path();
        final String path = isNullOrEmpty(pathForDiscovery) ? znodePath : znodePath + pathForDiscovery;
        try {
            pathChildrenCache = pathChildrenCache(path, discoverySpec);
            pathChildrenCache.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PathChildrenCache pathChildrenCache(String path, ZooKeeperDiscoverySpec spec) {
        final PathChildrenCache pathChildrenCache = new PathChildrenCache(client, path, true);
        pathChildrenCache.getListenable().addListener((c, event) -> {
            switch (event.getType()) {
                case CHILD_ADDED:
                    final Endpoint addedEndpoint = endpoint(spec, event);
                    if (addedEndpoint != null) {
                        addEndpoint(addedEndpoint);
                    }
                    break;
                case CHILD_REMOVED:
                    final Endpoint removedEndpoint = endpoint(spec, event);
                    if (removedEndpoint != null) {
                        removeEndpoint(removedEndpoint);
                    }
                    break;
                default:
                    break;
            }
        });
        return pathChildrenCache;
    }

    @Nullable
    private static Endpoint endpoint(ZooKeeperDiscoverySpec spec, PathChildrenCacheEvent event) {
        return spec.decode(event.getData().getData());
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        try {
            pathChildrenCache.close();
        } catch (IOException e) {
            logger.warn("Failed to close PathChildrenCache:", e);
        } finally {
            if (internalClient) {
                closeCuratorFrameworkThreadFactory.newThread(() -> {
                    try {
                        client.close();
                    } catch (Throwable cause) {
                        logger.warn("Failed to close CuratorFramework:", cause);
                    } finally {
                        future.complete(null);
                    }
                }).start();
            } else {
                future.complete(null);
            }
        }
    }
}
