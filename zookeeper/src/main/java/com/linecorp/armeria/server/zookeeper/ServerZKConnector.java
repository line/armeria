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
package com.linecorp.armeria.server.zookeeper;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZKConnector;
import com.linecorp.armeria.common.zookeeper.ZKListener;

/**
 * A Server connection maintains the underlying connection and hearing notice from a ZooKeeper cluster.
 */
public class ServerZKConnector {
    private final Endpoint endpoint;
    private final NodeValueCodec nodeValueCodec;
    private final ZKConnector zkConnector;

    /**
     * Create a server connector.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register information
     * @param nodeValueCodec           nodeValueCodec used
     */
    public ServerZKConnector(String zkConnectionStr, String zNodePath, int sessionTimeout, Endpoint endpoint,
                             NodeValueCodec nodeValueCodec) {
        zkConnector = new ZKConnector(zkConnectionStr, zNodePath, sessionTimeout, new ZKListener() {
            @Override
            public void nodeChildChange(Map<String, String> newChildrenValue) {
            }

            @Override
            public void nodeValueChange(String newValue) {
            }

            @Override
            public void connected() {
                zkConnector.createChild(endpoint.host() + '_' + endpoint.port(),
                                        nodeValueCodec.encode(endpoint));
            }
        });
        this.endpoint = requireNonNull(endpoint, "endpoint");
        this.nodeValueCodec = requireNonNull(nodeValueCodec, "nodeValueCodec");
        zkConnector.connect();
    }

    /**
     * Create a server connector.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register information
     */
    public ServerZKConnector(String zkConnectionStr, String zNodePath, int sessionTimeout, Endpoint endpoint) {
        this(zkConnectionStr, zNodePath, sessionTimeout, endpoint, NodeValueCodec.DEFAULT);
    }

    public void close(boolean active) {
        zkConnector.close(active);
    }

    @VisibleForTesting
    public void enableStateRecording() {
        zkConnector.enableStateRecording();
    }

    @VisibleForTesting
    public ZooKeeper underlyingClient() {
        return zkConnector.underlyingClient();
    }

    @VisibleForTesting
    public BlockingQueue<KeeperState> stateQueue() {
        return zkConnector.stateQueue();
    }
}
