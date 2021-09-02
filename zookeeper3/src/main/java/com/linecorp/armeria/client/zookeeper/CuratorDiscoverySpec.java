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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.zookeeper.CuratorXNodeValueCodec;

final class CuratorDiscoverySpec implements ZooKeeperDiscoverySpec {

    private static final Logger logger = LoggerFactory.getLogger(CuratorDiscoverySpec.class);

    private final String path;
    private final Function<? super ServiceInstance<?>, @Nullable Endpoint> converter;

    CuratorDiscoverySpec(
            String serviceName, Function<? super ServiceInstance<?>, @Nullable Endpoint> converter) {
        path = '/' + serviceName;
        this.converter = converter;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Endpoint decode(byte[] data) {
        final ServiceInstance<?> decodedInstance = CuratorXNodeValueCodec.INSTANCE.decode(data);
        @Nullable
        final Endpoint endpoint = converter.apply(decodedInstance);
        if (endpoint == null) {
            logger.warn("The endpoint converter returned null from {}.", decodedInstance);
        }
        return endpoint;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("converter", converter)
                          .toString();
    }
}
