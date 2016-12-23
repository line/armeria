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
package com.linecorp.armeria.client.endpoint.zookeeper.common;

import static java.util.Objects.requireNonNull;
import static org.apache.zookeeper.KeeperException.Code.get;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

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

import com.linecorp.armeria.client.endpoint.EndpointGroupException;

/**
 * A ZooKeeper connector, maintains a ZooKeeper connection.
 */
public abstract class Connector {

    private static final Logger logger = LoggerFactory.getLogger(Connector.class);

    private static final int MAX_RETRY_DELAY_MILLIS = 60 * 1000; // One minute
    private final String zkConnectionStr;



    private final String zNodePath;
    private final int sessionTimeout;
    private int retryDelayMills = 1000; // Start with one second
    private ZooKeeper zooKeeper;
    private BlockingQueue<KeeperState> stateQueue;
    private CountDownLatch latch;
    private boolean activeClose;

    /**
     * Creates a connector
     *
     * @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeperProxy server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     */
    protected Connector(String zkConnectionStr, String zNodePath, int sessionTimeout
    ) {

        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.sessionTimeout = sessionTimeout;

    }

    /**
     * Do connect.
     */
    protected void connect() {

        try {
            activeClose = false;
            latch = new CountDownLatch(1);
            zooKeeper = new ZooKeeper(zkConnectionStr, sessionTimeout, new ZkWatcher());
            latch.await();
            postConnected(zooKeeper);
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

    protected void resetRetryDelay() {
        retryDelayMills = 1000;
    }

    /**
     * Do a synchronized wait.
     */
    protected void doWait() {
        // Recover the ZooKeeperProxy connection using an exponential back-off strategy.
        try {
            Thread.sleep(retryDelayMills);
        } catch (InterruptedException e) {
            throw new ZooKeeperException("Failed to recover a ZooKeeperProxy connection", e);
        }
        retryDelayMills = Math.min(MAX_RETRY_DELAY_MILLIS, retryDelayMills * 2);
    }

    /**
     *  Closes the underlying Zookeeper connection.
     */
    public void close(boolean active) {
        try {
            activeClose = active;
            zooKeeper.close();

        } catch (Exception e) {
            throw new EndpointGroupException("Failed to close underlying ZooKeeperProxy connection", e);
        }

    }

    protected String getzNodePath() {
        return zNodePath;
    }

    protected abstract void postConnected(ZooKeeper zooKeeper);

    protected abstract void nodeChange(ZooKeeper zooKeeper, String path);

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
                        retryDelayMills = 1000;
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
                            logger.warn("Failed to attempt to recover a ZooKeeperProxy connection", e);
                        }
                        break;
                }
            } else {
                if (path != null && path.startsWith(zNodePath)) {
                    // Something has changed on the node, let's find out.
                    try {
                        zooKeeper.exists(path, true, this, null);
                    } catch (Exception e) {
                        throw new EndpointGroupException("Failed to process a ZooKeeperProxy watch event", e);
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
                        throw new ZooKeeperException("Failed to process ZooKeeperProxy callback event", ex);
                    }
                    return;
            }

            if (!activeClose) {
                nodeChange(zooKeeper, path);
                //enqueue an end flag to force the main thread to wait until this callback finished  before exit
                if (stateQueue != null) {
                    enqueueState(KeeperState.Disconnected);
                }
            }

        }

        /**
         * Enqueue the state.
         * @param state ZooKeeperProxy state
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

