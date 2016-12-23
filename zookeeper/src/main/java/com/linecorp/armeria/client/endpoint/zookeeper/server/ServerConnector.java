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
package com.linecorp.armeria.client.endpoint.zookeeper.server;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.zookeeper.common.Codec;
import com.linecorp.armeria.client.endpoint.zookeeper.common.Connector;
import com.linecorp.armeria.client.endpoint.zookeeper.common.DefaultCodec;
import com.linecorp.armeria.client.endpoint.zookeeper.common.ZooKeeperException;

public class ServerConnector extends Connector {
    private final Endpoint endpoint;
    private final Codec codec;

    /**
     * Create a server connector.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register information
     * @param codec           codec used
     */
    public ServerConnector(String zkConnectionStr, String zNodePath, int sessionTimeout, Endpoint endpoint,
                           Codec codec) {
        super(zkConnectionStr, zNodePath, sessionTimeout);
        this.endpoint = requireNonNull(endpoint, "endpoint");
        this.codec = requireNonNull(codec, "codec");
    }

    /**
     * Create a server connector.
     * @param zkConnectionStr ZooKeeper connection string
     * @param zNodePath       ZooKeeper node path(under which this server will registered)
     * @param sessionTimeout  session timeout
     * @param endpoint        register information
     */
    public ServerConnector(String zkConnectionStr, String zNodePath, int sessionTimeout, Endpoint endpoint) {
        this(zkConnectionStr, zNodePath, sessionTimeout, endpoint, new DefaultCodec());
        connect();
    }

    @Override
    protected void postConnected(ZooKeeper zooKeeper) {
        try {
            //first check the parent node
            if (zooKeeper.exists(getzNodePath(), false) == null) {
                //parent node not exist, create it
                try {
                    zooKeeper.create(getzNodePath(), endpoint.host().getBytes(StandardCharsets.UTF_8),
                                     Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT);
                } catch (KeeperException exception) {
                    //other server has created the node in concurrently
                }
            }
            checkNode(zooKeeper);
        } catch (Exception ex) {
            throw new ZooKeeperException(ex);
        }

    }

    @Override
    protected void nodeChange(ZooKeeper zooKeeper, String path) {
        if (path.equals(getzNodePath() + '/' + endpoint.host() + '_' + endpoint.port())) {
            //child node changed
            try {
                //check node
                checkNode(zooKeeper);
            } catch (Exception e) {
                throw new ZooKeeperException(e);
            }
        }
    }

    private void checkNode(ZooKeeper zooKeeper) throws KeeperException, InterruptedException {
        //parent node exist, register the current host, and leave a watch on it
        if (zooKeeper.exists(getzNodePath() + '/' + endpoint.host() + '_' + endpoint.port(), true) == null) {
            zooKeeper.create(getzNodePath() + '/' + endpoint.host() + '_' + endpoint.port(),
                             codec.encode(endpoint),
                             Ids.OPEN_ACL_UNSAFE,
                             CreateMode.EPHEMERAL);

        }

    }

}
