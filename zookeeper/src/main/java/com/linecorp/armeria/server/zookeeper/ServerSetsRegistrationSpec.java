/*
 * Copyright 2020 LINE Corporation
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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.zookeeper.ServerSetsInstance;
import com.linecorp.armeria.internal.common.zookeeper.ServerSetsNodeValueCodec;

final class ServerSetsRegistrationSpec implements ZooKeeperRegistrationSpec {

    private final String path;
    private final boolean isSequential;
    private final ServerSetsInstance serverSetsInstance;

    ServerSetsRegistrationSpec(String nodeName, boolean isSequential,
                               ServerSetsInstance serverSetsInstance) {
        path = '/' + nodeName;
        this.isSequential = isSequential;
        this.serverSetsInstance = serverSetsInstance;
    }

    ServerSetsInstance serverSetsInstance() {
        return serverSetsInstance;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public boolean isSequential() {
        return isSequential;
    }

    @Override
    public byte[] encodedInstance() {
        return ServerSetsNodeValueCodec.INSTANCE.encode(serverSetsInstance);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serverSetsInstance", serverSetsInstance)
                          .add("path", path)
                          .add("isSequential", isSequential)
                          .toString();
    }
}
