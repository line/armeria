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

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;

final class ClusterResourceParser extends ResourceParser<Cluster, ClusterXdsResource> {

    static final ClusterResourceParser INSTANCE = new ClusterResourceParser();

    private ClusterResourceParser() {}

    @Override
    ClusterXdsResource parse(Cluster cluster) {
        final ClusterXdsResource resource = new ClusterXdsResource(cluster);
        if (cluster.hasEdsClusterConfig()) {
            final EdsClusterConfig eds = cluster.getEdsClusterConfig();
            XdsConverterUtil.validateConfigSource(eds.getEdsConfig());
        }
        return resource;
    }

    @Override
    String name(Cluster cluster) {
        return cluster.getName();
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
