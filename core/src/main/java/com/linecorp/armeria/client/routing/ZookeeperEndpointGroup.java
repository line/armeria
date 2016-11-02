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
package com.linecorp.armeria.client.routing;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;

/**
 *  a zookeeper endpoint group implementation
 */
public class ZookeeperEndpointGroup implements EndpointGroup, Watcher, StatCallback {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperEndpointGroup.class);

    private String hostPort;
    private String znode;
    private List<Endpoint> endpointList;
    private ZooKeeper zooKeeper;
    byte prevData[];
    Function<byte[], List<Endpoint>> nodeValueToEndpointList;

    final CountDownLatch connectionLatch = new CountDownLatch(1);

    public ZookeeperEndpointGroup(String hostPort, String znode, int sessionTimeout,
                                  Function<byte[], List<Endpoint>> nodeValueToEndpointList) {

        requireNonNull(hostPort);
        requireNonNull(znode);
        requireNonNull(nodeValueToEndpointList);
        this.hostPort = hostPort;
        this.znode = znode;
        this.nodeValueToEndpointList = nodeValueToEndpointList;
        try {
            zooKeeper = new ZooKeeper(hostPort, sessionTimeout, event -> {
                if (event.getState() == KeeperState.SyncConnected) {
                    connectionLatch.countDown();
                }

            });
            connectionLatch.await();

            Stat stat = zooKeeper.exists(znode, this);
            byte[] nodeData;
            if (stat != null) {
                nodeData = zooKeeper.getData(znode, false, null);
                endpointList = nodeValueToEndpointList.apply(nodeData);
            }

        } catch (IOException e) {
            logger.error("error occurred while connecting zooKeeper " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException ignored) {} catch (KeeperException ke) {
            logger.error("error occurred while reading data from zookeeper ");
        }

    }

    @Override
    public List<Endpoint> endpoints() {
        return this.endpointList;

    }

    @Override
    public void process(WatchedEvent event) {
        String path = event.getPath();
        if (event.getType() != Event.EventType.None) {
            if (path != null && path.equals(znode)) {
                // Something has changed on the node, let's find out
                zooKeeper.exists(znode, true, this, null);
            }
        }

    }

    @Override
    public void processResult(int responseCodeInt, String path, Object ctx, Stat stat) {
        boolean exists;
        Code responseCode = Code.get(responseCodeInt);
        switch (responseCode) {
            case OK:
                exists = true;
                break;
            case NONODE:
                exists = false;
                break;
            case SESSIONEXPIRED:
            case NOAUTH:
                return;
            default:
                // Retry errors
                zooKeeper.exists(znode, true, this, null);
                return;
        }

        byte nodeData[] = null;
        if (exists) {
            try {
                nodeData = zooKeeper.getData(znode, false, null);
            } catch (KeeperException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                return;
            }
        }
        if ((nodeData == null && nodeData != prevData)
            || (nodeData != null && !Arrays.equals(prevData, nodeData))) {
            prevData = nodeData;
            this.endpointList = nodeValueToEndpointList.apply(prevData);
        }
    }
}
