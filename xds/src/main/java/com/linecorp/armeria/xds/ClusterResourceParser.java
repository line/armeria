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

import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;

final class ClusterResourceParser extends ResourceParser<Cluster, ClusterXdsResource> {

    private static final String HTTP_PROTOCOL_OPTIONS_KEY =
            "envoy.extensions.upstreams.http.v3.HttpProtocolOptions";

    static final ClusterResourceParser INSTANCE = new ClusterResourceParser();

    private ClusterResourceParser() {}

    @Override
    ClusterXdsResource parse(Cluster cluster, XdsExtensionRegistry registry, String version) {
        return new ClusterXdsResource(cluster, version, parseHttpProtocolOptions(cluster, registry));
    }

    @Nullable
    private static HttpProtocolOptions parseHttpProtocolOptions(Cluster cluster,
                                                                 XdsExtensionRegistry registry) {
        final Any any = cluster.getTypedExtensionProtocolOptionsMap().get(HTTP_PROTOCOL_OPTIONS_KEY);
        if (any == null) {
            return null;
        }
        return registry.unpack(any, HttpProtocolOptions.class);
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
