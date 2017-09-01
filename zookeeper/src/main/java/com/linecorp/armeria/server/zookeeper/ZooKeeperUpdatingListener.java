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

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;

/**
 * A ZooKeeper Server Listener.When you add this listener, server will be automatically registered
 * into the ZooKeeper.
 */
public class ZooKeeperUpdatingListener extends ServerListenerAdapter {
    private final String zkConnectionStr;
    private final String zNodePath;
    private final int sessionTimeout;
    private Endpoint endpoint;
    private ZooKeeperRegistration connector;

    /**
     * A ZooKeeper server listener, used for register server into ZooKeeper.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        the endpoint of the server being registered
     */
    public ZooKeeperUpdatingListener(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                     Endpoint endpoint) {
        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.endpoint = requireNonNull(endpoint, "endPoint");
        this.sessionTimeout = sessionTimeout;
    }

    /**
     * A ZooKeeper server listener, used for register server into ZooKeeper.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     */
    public ZooKeeperUpdatingListener(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this.zkConnectionStr = requireNonNull(zkConnectionStr, "zkConnectionStr");
        this.zNodePath = requireNonNull(zNodePath, "zNodePath");
        this.sessionTimeout = sessionTimeout;
    }

    @Override
    public void serverStarting(Server server) throws Exception {
        if (endpoint == null) {
            assert server.activePort().isPresent();
            endpoint = Endpoint.of(server.defaultHostname(),
                                   server.activePort().get()
                                         .localAddress().getPort());
        }
        connector = new ZooKeeperRegistration(zkConnectionStr, zNodePath, sessionTimeout, endpoint);
    }

    @Override
    public void serverStopping(Server server) throws Exception {
        if (connector != null) {
            connector.close(true);
        }
    }

    @VisibleForTesting
    ZooKeeperRegistration getConnector() {
        return connector;
    }

    @VisibleForTesting
    Endpoint getEndpoint() {
        return endpoint;
    }
}
