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
package com.linecorp.armeria.server.zookeeper.listener;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.zookeeper.ServerZKConnector;

/**
 * A ZooKeeper Server Listener.When you add this listener, server will be automatically registered
 * into the ZooKeeper.
 */
public class ZooKeeperListener implements ServerListener {

    private final String zkConnectionStr;
    private final String zNodePath;
    private final int sessionTimeout;
    private final Endpoint endpoint;
    private ServerZKConnector connector;

    /**
     * A ZooKeeper server listener, used for register server into ZooKeeper.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register endpoint information
     */
    public ZooKeeperListener(String zkConnectionStr, String zNodePath, int sessionTimeout,
                             Endpoint endpoint) {
        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.endpoint = requireNonNull(endpoint, "endpoint");
        this.sessionTimeout = sessionTimeout;
    }

    @Override
    public void serverStarting(Server server) throws Exception {
        connector = new ServerZKConnector(zkConnectionStr, zNodePath, sessionTimeout, endpoint);
    }

    @Override
    public void serverStarted(Server server) throws Exception {
    }

    @Override
    public void serverStopping(Server server) throws Exception {
        connector.close(true);
    }

    @Override
    public void serverStopped(Server server) throws Exception {
    }

    @VisibleForTesting
    public ServerZKConnector getConnector() {
        return connector;
    }

    @VisibleForTesting
    public Endpoint getEndpoint() {
        return endpoint;
    }
}
