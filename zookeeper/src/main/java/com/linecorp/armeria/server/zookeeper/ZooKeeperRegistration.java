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
package com.linecorp.armeria.server.zookeeper;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZooKeeperConnector;
import com.linecorp.armeria.common.zookeeper.ZooKeeperListener;

/**
 * A Server connection maintains the underlying connection and hearing notice from a ZooKeeper cluster.
 */
class ZooKeeperRegistration {
    private final ZooKeeperConnector zooKeeperConnector;

    /**
     * Create a server register.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register information
     * @param nodeValueCodec  nodeValueCodec used
     */
    ZooKeeperRegistration(String zkConnectionStr, String zNodePath, int sessionTimeout, Endpoint endpoint,
                          NodeValueCodec nodeValueCodec) {
        requireNonNull(nodeValueCodec);
        requireNonNull(endpoint);
        zooKeeperConnector = new ZooKeeperConnector(zkConnectionStr, zNodePath, sessionTimeout,
                                                    new ZooKeeperListener() {
                                                        @Override
                                                        public void nodeChildChange(
                                                                Map<String, String> newChildrenValue) {
                                                        }

                                                        @Override
                                                        public void nodeValueChange(String newValue) {
                                                        }

                                                        @Override
                                                        public void connected() {
                                                            zooKeeperConnector.createChild(
                                                                    endpoint.host() + '_' + endpoint.port(),
                                                                    nodeValueCodec.encode(endpoint));
                                                        }
                                                    });
        zooKeeperConnector.connect();
    }

    /**
     * Create a server register.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register information
     */
    ZooKeeperRegistration(String zkConnectionStr, String zNodePath, int sessionTimeout, Endpoint endpoint) {
        this(zkConnectionStr, zNodePath, sessionTimeout, endpoint, NodeValueCodec.DEFAULT);
    }

    public void close(boolean active) {
        zooKeeperConnector.close(active);
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
