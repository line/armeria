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
package com.linecorp.armeria.common.zookeeper;

import static java.util.Objects.requireNonNull;
import static org.apache.zookeeper.KeeperException.Code.get;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.endpoint.EndpointGroupException;

/**
 * A ZooKeeper connector, maintains a ZooKeeper connection.
 */
public final class ZooKeeperConnector {
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperConnector.class);
    private final String zkConnectionStr;
    private final String zNodePath;
    private final int sessionTimeout;
    private final ZooKeeperListener listener;
    private ZooKeeper zooKeeper;
    private BlockingQueue<KeeperState> stateQueue;
    private CountDownLatch latch;
    private boolean activeClose;
    private String prevNodeValue;
    private Map<String, String> prevChildValue;

    /**
     * Creates a connector.
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     * @param listener        {@link ZooKeeperListener}
     */
    public ZooKeeperConnector(String zkConnectionStr, String zNodePath, int sessionTimeout,
                              ZooKeeperListener listener
    ) {
        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.sessionTimeout = sessionTimeout;
        this.listener = requireNonNull(listener, "listener");
    }

    /**
     * Do connect.
     */
    public void connect() {
        try {
            activeClose = false;
            latch = new CountDownLatch(1);
            zooKeeper = new ZooKeeper(zkConnectionStr, sessionTimeout, new ZkWatcher());
            latch.await();
            notifyConnected();
            notifyChange();
            if (stateQueue != null) {
                //put a fake stat to ensure method postConnected finished completely
                //(so that it won't throw exception under ZooKeeper connection recovery test)
                stateQueue.put(KeeperState.Disconnected);
            }
        } catch (Exception e) {
            throw new ZooKeeperException(
                    "failed to connect to ZooKeeper:  " + zkConnectionStr + " (" + e + ')', e);
        }
    }

    /**
     * Utility method to create a node.
     * @param nodePath node name
     * @param value    node value
     */
    public void createChild(String nodePath, byte[] value) {
        try {
            //first check the parent node
            if (zooKeeper.exists(zNodePath, false) == null) {
                //parent node not exist, create it
                zooKeeper.create(zNodePath, zNodePath.getBytes(StandardCharsets.UTF_8),
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT);
            }
            if (zooKeeper.exists(zNodePath + '/' + nodePath, true) == null) {
                zooKeeper.create(zNodePath + '/' + nodePath, value, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            }
        } catch (Exception e) {
            throw new ZooKeeperException(
                    "failed to create ZooKeeper Node:  " + zkConnectionStr + " (" + e + ')', e);
        }
    }

    /**
     * Closes the underlying Zookeeper connection.
     * @param active if it is closed by user actively ? or passively by program because of underlying
     *               connection expires
     */
    public void close(boolean active) {
        try {
            activeClose = active;
            zooKeeper.close();
        } catch (Exception e) {
            throw new EndpointGroupException("Failed to close underlying ZooKeeper connection", e);
        }
    }

    /**
     * Notify listener that ZooKeeper connection has been established.
     */
    private void notifyConnected() {
        listener.connected();
    }

    /**
     * Notify listener that a node value or node children has changed.
     */
    private void notifyChange() {
        //forget it if event was triggered by user's actively closing EndpointGroup
        if (activeClose) {
            return;
        }
        List<String> children;
        byte[] newValueBytes;
        try {
            if (zooKeeper.exists(zNodePath, true) == null) {
                return;
            }
            children = zooKeeper.getChildren(zNodePath, true);
            newValueBytes = zooKeeper.getData(zNodePath, false, null);
            if (newValueBytes != null) {
                String newValue = new String(newValueBytes, StandardCharsets.UTF_8);
                if (prevNodeValue == null || !prevNodeValue.equals(newValue)) {
                    listener.nodeValueChange(newValue);
                    prevNodeValue = newValue;
                }
            }
            //check children status
            if (children != null) {
                Map<String, String> newChildValue = new HashMap<>();
                children.forEach(child -> {
                    try {
                        newChildValue.put(child,
                                          new String(zooKeeper.getData(zNodePath + '/' + child,
                                                                       false, null), StandardCharsets.UTF_8));
                    } catch (KeeperException.NoNodeException e) {
                        // Skip the node which got deleted between getChildren() and getData().
                    } catch (Exception e) {
                        throw new ZooKeeperException("Failed to retrieve the data from: " +
                                                     zNodePath + '/' + child, e);
                    }
                });
                if (prevChildValue == null || !prevChildValue.equals(newChildValue)) {
                    listener.nodeChildChange(newChildValue);
                    prevChildValue = newChildValue;
                }
            }
        } catch (ZooKeeperException e) {
            throw e;
        } catch (Exception e) {
            throw new ZooKeeperException("Failed to notify ZooKeeper listener", e);
        }
    }

    /**
     * A ZooKeeper watch.
     */
    final class ZkWatcher implements Watcher, StatCallback {
        @Override
        public void process(WatchedEvent event) {
            if (stateQueue != null) {
                enqueueState(event.getState());
            }
            String path = event.getPath();
            if (event.getType() == Event.EventType.None) {
                // Connection state has been changed. Keep retrying until the connection is recovered.
                switch (event.getState()) {
                    case Disconnected:
                        break;
                    case SyncConnected:
                        // We are here because of one of the following:
                        // - initial connection,
                        // - reconnection due to session timeout or
                        // - reconnection due to session expiration
                        // Once connected, reset the retry delay.
                        latch.countDown();
                        break;
                    case Expired:
                        // Session expired usually happens when a client reconnected to the server after
                        // long time network partition, exceeding the configured
                        // session timeout. We need to reconstruct the ZooKeeper client.
                        // First, clean the original handle.
                        close(false);
                        zooKeeper = null;
                        try {
                            if (!activeClose) {
                                connect();
                            }
                        } catch (ZooKeeperException e) {
                            logger.warn("Failed to attempt to recover a ZooKeeper connection", e);
                        }
                        break;
                }
            } else {
                if (path != null && path.startsWith(zNodePath)) {
                    // Something has changed on the node, let's find out.
                    try {
                        zooKeeper.exists(path, true, this, null);
                    } catch (Exception e) {
                        throw new EndpointGroupException("Failed to process a ZooKeeper watch event", e);
                    }
                }
            }
        }

        @Override
        public void processResult(int responseCodeInt, String path, Object ctx, Stat stat) {
            Code responseCode = get(responseCodeInt);
            switch (responseCode) {
                case OK:
                    break;
                case NONODE:
                    break;
                case SESSIONEXPIRED:
                    // Ignore this and let the zNode Watcher process it first.
                case NOAUTH:
                    // It's possible that this happens during runtime. We ignore this and wait for the ACL
                    // configuration returns to normal. If it happens when the ZooKeeper client is initially
                    // constructed, the constructor will throw an exception.
                    return;
                default:
                    // Retry on recoverable errors. Fatal errors go to the process() method above.
                    try {
                        zooKeeper.exists(path, true, this, null);
                    } catch (Exception ex) {
                        throw new ZooKeeperException("Failed to process ZooKeeper callback event", ex);
                    }
                    return;
            }
            if (!activeClose) {
                notifyChange();
                //enqueue an end flag to force the main thread to wait until this callback finished  before exit
                if (stateQueue != null) {
                    enqueueState(KeeperState.Disconnected);
                }
            }
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

    @VisibleForTesting
    public BlockingQueue<KeeperState> stateQueue() {
        return stateQueue;
    }

    /**
     * Open state recording.
     */
    @VisibleForTesting
    public void enableStateRecording() {
        if (stateQueue == null) {
            stateQueue = new ArrayBlockingQueue<>(10);
        }
    }

    @VisibleForTesting
    public ZooKeeper underlyingClient() {
        return zooKeeper;
    }
}
