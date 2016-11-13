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
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.routing.EndpointGroup;
import com.linecorp.armeria.client.routing.EndpointGroupException;

/**
 * A ZooKeeper based {@link EndpointGroup} implementation.
 * It will get the {@link EndpointGroup} information from a Zookeeper zNode, and any update to it will
 * be also reflected asynchronously to the group.
 * When a ZooKeeper session expired event occurs, it will automatically reconnect to its server,
 * and also rebuild the builtin watcher on the zNode
 */
public class ZookeeperEndpointGroup implements EndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperEndpointGroup.class);

    private static final int MAX_RETRY_DELAY_MILLIS = 60 * 1000; //one minute
    private int retryDelayMills = 1000; //start with one second
    private final String zkConnectionStr;
    private final String zNode;
    private final int sessionTimeout;
    private final ZkNodeValueConverter converter;
    private List<Endpoint> endpointList;
    private ZooKeeper zooKeeper;
    private byte[] prevData;
    private CompletableFuture<ZooKeeper> zkHandleProxy = new CompletableFuture<>();
    private BlockingQueue<KeeperState> statesQueue;

    /**
     * Create a Zookeeper endpoint group with a {@link DefaultZkNodeValueConverter}.
     * @param zkConnectionStr A connection string containing a comma
     *                          separated list of host:port pairs , each corresponding to a ZooKeeper server
     * @param zNode a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout   Zookeeper session timeout in milliseconds
     */
    public ZookeeperEndpointGroup(String zkConnectionStr, String zNode, int sessionTimeout,
                                  ZkNodeValueConverter converter) {
        this(zkConnectionStr, zNode, sessionTimeout, converter, false);
    }

    /**
     * Create a Zookeeper endpoint group with a {@link DefaultZkNodeValueConverter}.
     * @param zkConnectionStr A connection string containing a comma
     *                          separated list of host:port pairs , each corresponding to a ZooKeeper server
     * @param zNode a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout   Zookeeper session timeout in milliseconds
     */
    public ZookeeperEndpointGroup(String zkConnectionStr, String zNode, int sessionTimeout) {
        this(zkConnectionStr, zNode, sessionTimeout, new DefaultZkNodeValueConverter(), false);
    }

    /**
     * Creates a {@link ZookeeperEndpointGroup}.
     * @param zkConnectionStr A connection string containing a comma
     *                          separated list of host:port pairs , each corresponding to a ZooKeeper server
     * @param zNode a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout   Zookeeper session timeout in milliseconds
     * @param converter a function to convert zNode value to a List of {@link Endpoint}
     * @param openStatesStack whether opens the states stack for testing
     */
    private ZookeeperEndpointGroup(String zkConnectionStr, String zNode, int sessionTimeout,
                                   ZkNodeValueConverter converter, boolean openStatesStack) {
        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNode = requireNonNull(zNode, "zNode");
        this.converter = requireNonNull(converter, "converter");
        this.sessionTimeout = sessionTimeout;
        if (openStatesStack) {
            statesQueue = new ArrayBlockingQueue<>(10);
        }
        doConnect();
    }

    /**
     *<h3>note:</h3>.
     *This method could be invoked in the callback thread instead of the main thread,
     *The resetting operation of the Zookeeper client handler could cause a race condition between the
     *two threads,we need to use a proxy to protect it
     */
    private void doConnect() {
        try {
            zooKeeper = new ZooKeeper(zkConnectionStr, sessionTimeout, new ZkWatcher());

            Stat stat = zkHandleProxy.get().exists(zNode, true);
            byte[] nodeData;
            if (stat != null) {
                nodeData = zkHandleProxy.get().getData(zNode, false, null);
                endpointList = converter.convert(nodeData);
            }
        } catch (Exception e) {
            throw new EndpointGroupException(
                    "failed to connect to ZooKeeper:  " + zkConnectionStr + " with error:  " + e.getMessage());
        }

    }

    @Override
    public List<Endpoint> endpoints() {
        return endpointList;
    }

    final class ZkWatcher implements Watcher, StatCallback {

        @Override
        public void process(WatchedEvent event) {
            String path = event.getPath();
            if (event.getType() == Event.EventType.None) {
                // We are are being told that the state of the connection has changed
                // note when a network partition occurs, ZooKeeper client will automatic reconnect the server
                //until connection recovered
                switch (event.getState()) {
                    case Disconnected:
                        if (statesQueue != null) {
                            enqueueState(Disconnected);
                        }
                        break;
                    case SyncConnected:
                        if (statesQueue != null) {
                            enqueueState(SyncConnected);
                        }
                        //3 types syncConnected:  application starting time connect,reconnect within session
                        //timeout time ,reconnect after session expired
                        zkHandleProxy.complete(zooKeeper);
                        //once connected, reset the retry delay for the next time
                        retryDelayMills = 1000;
                        break;
                    case Expired:
                        //session expired usually happens when a client reconnect to the server after
                        //a client-server network partition recover, but the recovering time exceed
                        //session timeout. It's all over, we need reconstruct the ZooKeeper client handle.
                        //first clean the original handle
                        if (statesQueue != null) {
                            enqueueState(Expired);
                        }
                        close();
                        zooKeeper = null;
                        zkHandleProxy = new CompletableFuture<>();
                        while (zooKeeper == null) {
                            try {
                                recoverZkConnection();
                            } catch (EndpointGroupException ex) {
                                logger.warn("Failed to attempt to recover ZooKeeper connection:  " + ex
                                        .getMessage());
                            }
                        }
                        break;
                }
            } else {
                if (path != null && path.equals(zNode)) {
                    // Something has changed on the node, let's find out
                    try {
                        zkHandleProxy.get().exists(zNode, true, this, null);
                    } catch (Exception e) {
                        throw new EndpointGroupException("Failed to process ZooKeeper watch event", e);
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
                    //ignore this and let the zNode Watcher process it first
                case NOAUTH:
                    //possible happens during application runtime, we ignore this and wait
                    //for the ACL returned to normal. When it happens at application Start time, the
                    //ZookeeperEndpointGroup class constructor will throw an exception
                    return;
                default:
                    //recoverable errors, retry it, fatal errors goes to Watcher process routine
                    try {
                        zkHandleProxy.get().exists(zNode, true, this, null);
                    } catch (Exception ex) {
                        throw new EndpointGroupException("Failed to process ZooKeeper callback event", ex);
                    }
                    return;
            }
            byte[] nodeData = null;
            if (exists) {
                try {
                    nodeData = zkHandleProxy.get().getData(zNode, false, null);
                } catch (Exception e) {
                    logger.warn("Failed to get zNode data:  " + e.getMessage());
                    //just return, fatal errors goes to Watcher process routine
                    return;
                }
            }
            if (nodeData == null && nodeData != prevData ||
                nodeData != null && !Arrays.equals(prevData, nodeData)) {
                prevData = nodeData;
                try {
                    endpointList = converter.convert(prevData);
                } catch (IllegalArgumentException exception) {
                    logger.warn("Failed to convert zNode value to EndpointGroup:  " + exception.getMessage() +
                                ", invalid value:  " + new String(prevData, Charsets.UTF_8));
                }
            }
        }

        private void recoverZkConnection() {
            //recoverZkConnection  by using a exponential backoff strategy
            try {
                Thread.sleep(retryDelayMills);
            } catch (InterruptedException e) {
                throw new EndpointGroupException("Failed to recover ZooKeeper connection", e);
            }
            retryDelayMills = Math.min(MAX_RETRY_DELAY_MILLIS, retryDelayMills * 2);
            doConnect();
        }

        /**
         * Enqueue the state.
         * @param state ZooKeeper state
         */
        private void enqueueState(KeeperState state) {
            try {
                statesQueue.put(state);
            } catch (InterruptedException e) {
                throw new EndpointGroupException("Failed to enqueue the state.", e);
            }
        }

    }

    /**
     *  Closes the underlying Zookeeper connection.
     */
    public void close() {
        try {
            //protect the ZooKeeper client handler from the main thread
            zkHandleProxy.get().close();
        } catch (Exception e) {
            throw new EndpointGroupException("Failed to close underlying ZooKeeper connection", e);
        }
    }

    /**
     * Get the underlying ZooKeeper connection status.
     * @return ZooKeeper connection status
     */
    public CompletableFuture<String> getZKStatus() {
        //protect the ZooKeeper client handler from the main thread
        return zkHandleProxy.thenApply(handler -> handler.getState().toString());
    }

    @Override
    public String toString() {
        return Joiner.on(";").join(endpoints()) + " with Zookeeper connection string:  " + zkConnectionStr;
    }

    /**
     * Get the ZooKeeper handler.
     * @return the handler
     */
    @VisibleForTesting
    CompletableFuture<ZooKeeper> getZkHandler() {
        return zkHandleProxy;
    }

    /**
     * Create a ZookeeperEndpointGroup with statesStack feature.
     * @param zkConnectionStr connection string
     * @param zNode the zNode
     * @param sessionTimeout session timeout in millisecond
     * @param openStatesStack whether open statesStack
     */
    @VisibleForTesting
    ZookeeperEndpointGroup(String zkConnectionStr, String zNode, int sessionTimeout,
                           boolean openStatesStack) {
        this(zkConnectionStr, zNode, sessionTimeout, new DefaultZkNodeValueConverter(), openStatesStack);
    }

    /**
     * Get the states stack.
     * @return the stack
     */
    @VisibleForTesting
    BlockingQueue<KeeperState> getStatesQueue() {
        return statesQueue;
    }
}
