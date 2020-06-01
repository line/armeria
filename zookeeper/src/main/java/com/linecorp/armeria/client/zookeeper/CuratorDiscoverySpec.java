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
package com.linecorp.armeria.client.zookeeper;

import java.util.function.Function;

import org.apache.curator.x.discovery.ServiceInstance;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.common.zookeeper.CuratorXNodeValueCodec;

final class CuratorDiscoverySpec implements ZookeeperDiscoverySpec {

    private final String path;
    private final Function<? super ServiceInstance<?>, Endpoint> converter;

    CuratorDiscoverySpec(
            String serviceName, Function<? super ServiceInstance<?>, Endpoint> converter) {
        path = '/' + serviceName;
        this.converter = converter;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Endpoint decode(byte[] data) {
        return converter.apply(CuratorXNodeValueCodec.INSTANCE.decode(data));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("converter", converter)
                          .toString();
    }
}
