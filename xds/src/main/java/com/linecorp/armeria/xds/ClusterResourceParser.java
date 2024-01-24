/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.google.protobuf.Message;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.ClusterOrBuilder;

final class ClusterResourceParser extends ResourceParser {

    static final ClusterResourceParser INSTANCE = new ClusterResourceParser();

    private ClusterResourceParser() {}

    @Override
    ClusterResourceHolder parse(Message message) {
        if (!(message instanceof Cluster)) {
            throw new IllegalArgumentException("message not type of Cluster");
        }
        final Cluster cluster = (Cluster) message;
        final ClusterResourceHolder holder = new ClusterResourceHolder(cluster);
        if (cluster.hasEdsClusterConfig()) {
            final EdsClusterConfig eds = cluster.getEdsClusterConfig();
            XdsConverterUtil.validateConfigSource(eds.getEdsConfig());
        }
        return holder;
    }

    @Override
    String name(Message message) {
        if (!(message instanceof Cluster)) {
            throw new IllegalArgumentException("message not type of Cluster");
        }
        return ((ClusterOrBuilder) message).getName();
    }

    @Override
    Class<Cluster> clazz() {
        return Cluster.class;
    }

    @Override
    boolean isFullStateOfTheWorld() {
        return true;
    }

    @Override
    XdsType type() {
        return XdsType.CLUSTER;
    }
}
