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
/**
 * Automatically server registration by using ZooKeeper.
 * {@link com.linecorp.armeria.server.zookeeper.ZooKeeperUpdatingListener} can automatically register a
 * server to a ZooKeeper cluster. The registered ZooKeeper node is EPHEMERAL, so when server stops or a network
 * partition occurs, the underlying ZooKeeper session will be closed and the node will be automatically removed
 * by ZooKeeper cluster. As a result, the clients that use a ZooKeeperEndpointGroup will be notified and they
 * will update their endpoint list automatically, so that they do not attempt to connect to the unreachable
 * servers.
 *
 */
package com.linecorp.armeria.server.zookeeper;
