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
import static org.apache.zookeeper.Watcher.Event.KeeperState.Disconnected;
import static org.apache.zookeeper.Watcher.Event.KeeperState.Expired;
import static org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper zNode and updates it when the value of the zNode changes. When a
 * ZooKeeper session expires, it will automatically reconnect to the ZooKeeper with exponential retry delay,
 * starting from 1 second up to 60 seconds.
 */
public class ZooKeeperEndpointGroup implements EndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperEndpointGroup.class);

    private static final int MAX_RETRY_DELAY_MILLIS = 60 * 1000; // One minute
    private int retryDelayMills = 1000; // Start with one second
    private final String zkConnectionStr;
    private final String zNodePath;
    private final int sessionTimeout;
    private final ZooKeeperNodeValueConverter converter;
    private List<Endpoint> endpointList;
    private ZooKeeper zooKeeper;
    private byte[] prevData;
    private CompletableFuture<ZooKeeper> zkFuture = new CompletableFuture<>();
    private BlockingQueue<KeeperState> stateQueue;

    /**
     * Creates a new instance.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param converter       a {@link ZooKeeperNodeValueConverter} that converts the value of zNode into
     *                        a list of {@link Endpoint}s
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  ZooKeeperNodeValueConverter converter) {
        this(zkConnectionStr, zNodePath, sessionTimeout, converter, false);
    }

    /**
     * Create a new instance with the {@link DefaultZooKeeperNodeValueConverter}.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     */
    public ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this(zkConnectionStr, zNodePath, sessionTimeout,
             new DefaultZooKeeperNodeValueConverter(), false);
    }

    /**
     * Creates a new instance.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param openStateQueue  whether to open the states queue for testing
     */
    @VisibleForTesting
    ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                           boolean openStateQueue) {
        this(zkConnectionStr, zNodePath, sessionTimeout,
             new DefaultZooKeeperNodeValueConverter(), openStateQueue);
    }

    /**
     * Creates a new instance.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param converter       a {@link ZooKeeperNodeValueConverter} that converts the value of zNode into
     *                        a list of {@link Endpoint}s
     * @param openStateQueue  whether to open the states queue for testing
     */
    private ZooKeeperEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                   ZooKeeperNodeValueConverter converter, boolean openStateQueue) {

        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.converter = requireNonNull(converter, "converter");
        this.sessionTimeout = sessionTimeout;
        if (openStateQueue) {
            stateQueue = new ArrayBlockingQueue<>(10);
        }
        doConnect();
    }

    @VisibleForTesting
    CompletableFuture<ZooKeeper> zkFuture() {
        return zkFuture;
    }

    @VisibleForTesting
    BlockingQueue<KeeperState> stateQueue() {
        return stateQueue;
    }

    /**
     * <h3>Note:</h3>.
     * This method can be invoked in the callback thread instead of the main thread.
     * The resetting operation of the ZooKeeper client handler could cause a race condition between the
     * two threads, we need to use a proxy to prevent it.
     */
    private void doConnect() {
        try {
            zooKeeper = new ZooKeeper(zkConnectionStr, sessionTimeout, new ZkWatcher());

            final Stat stat = zkFuture.get().exists(zNodePath, true);
            if (stat != null) {
                final byte[] nodeData = zkFuture.get().getData(zNodePath, false, null);
                endpointList = converter.convert(nodeData);
            }
        } catch (Exception e) {
            throw new EndpointGroupException(
                    "failed to connect to ZooKeeper:  " + zkConnectionStr + " (" + e + ')', e);
        }
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpointList;
    }

    /**
     * Returns the connection state of the underlying ZooKeeper connection.
     */
    public CompletableFuture<ZooKeeper.States> zkStateFuture() {
        // protect the ZooKeeper client handler from the main thread
        return zkFuture.thenApply(ZooKeeper::getState);
    }

    /**
     *  Closes the underlying Zookeeper connection.
     */
    @Override
    public void close() {
        try {
            // protect the ZooKeeper client handler from the main thread
            zkFuture.get().close();
        } catch (Exception e) {
            throw new EndpointGroupException("Failed to close underlying ZooKeeper connection", e);
        }
    }

    @Override
    public String toString() {
        return Joiner.on(";").join(endpoints()) + " with Zookeeper connection string:  " + zkConnectionStr;
    }

    final class ZkWatcher implements Watcher, StatCallback {

        @Override
        public void process(WatchedEvent event) {
            String path = event.getPath();
            if (event.getType() == Event.EventType.None) {
                // Connection state has been changed. Keep retrying until the connection is recovered.
                switch (event.getState()) {
                    case Disconnected:
                        enqueueState(Disconnected);
                        break;
                    case SyncConnected:
                        enqueueState(SyncConnected);
                        // We are here because of one of the following:
                        // - initial connection,
                        // - reconnection due to session timeout or
                        // - reconnection due to session expiration
                        zkFuture.complete(zooKeeper);
                        // Once connected, reset the retry delay.
                        retryDelayMills = 1000;
                        break;
                    case Expired:
                        // Session expired usually happens when a client reconnects to the server when
                        // it took too long for a client to recover from network partition, exceeding the
                        // session timeout. We need to reconstruct the ZooKeeper client.
                        enqueueState(Expired);
                        // First, clean the original handle.
                        close();
                        zooKeeper = null;
                        zkFuture = new CompletableFuture<>();
                        while (zooKeeper == null) {
                            try {
                                recoverZkConnection();
                            } catch (EndpointGroupException e) {
                                logger.warn("Failed to attempt to recover a ZooKeeper connection", e);
                            }
                        }
                        break;
                }
            } else {
                if (path != null && path.equals(zNodePath)) {
                    // Something has changed on the node, let's find out.
                    try {
                        zkFuture.get().exists(zNodePath, true, this, null);
                    } catch (Exception e) {
                        throw new EndpointGroupException("Failed to process a ZooKeeper watch event", e);
                    }
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
                    // Ignore this and let the zNode Watcher process it first.
                case NOAUTH:
                    // It's possible that this happens during runtime. We ignore this and wait for the ACL
                    // configuration returns to normal. If it happens when the endpoint group is initially
                    // constructed, the constructor will throw an exception.
                    return;
                default:
                    // Retry on recoverable errors. Fatal errors go to the process() method above.
                    try {
                        zkFuture.get().exists(zNodePath, true, this, null);
                    } catch (Exception ex) {
                        throw new EndpointGroupException("Failed to process ZooKeeper callback event", ex);
                    }
                    return;
            }
            byte[] nodeData = null;
            if (exists) {
                try {
                    nodeData = zkFuture.get().getData(zNodePath, false, null);
                } catch (Exception e) {
                    logger.warn("Failed to get zNode data:  " + e.getMessage());
                    // Just return. Fatal errors go to the process() method above.
                    return;
                }
            }
            if (nodeData == null && nodeData != prevData ||
                nodeData != null && !Arrays.equals(prevData, nodeData)) {
                prevData = nodeData;
                try {
                    endpointList = converter.convert(prevData);
                } catch (Exception e) {
                    logger.warn("Failed to convert a zNode value at {} to an EndpointGroup: {}",
                                zNodePath, new String(prevData, StandardCharsets.UTF_8), e);
                }
            }
        }

        private void recoverZkConnection() {
            // Recover the ZooKeeper connection using an exponential back-off strategy.
            try {
                Thread.sleep(retryDelayMills);
            } catch (InterruptedException e) {
                throw new EndpointGroupException("Failed to recover a ZooKeeper connection", e);
            }
            retryDelayMills = Math.min(MAX_RETRY_DELAY_MILLIS, retryDelayMills * 2);
            doConnect();
        }

        /**
         * Enqueue the state.
         * @param state ZooKeeper state
         */
        private void enqueueState(KeeperState state) {
            if (stateQueue == null) {
                return;
            }

            try {
                stateQueue.put(state);
            } catch (InterruptedException e) {
                throw new EndpointGroupException("Failed to enqueue the state.", e);
            }
        }
    }
}
