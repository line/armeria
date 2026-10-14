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

import java.util.EnumSet;
import java.util.Set;

import com.google.protobuf.GeneratedMessageV3;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

/**
 * A representation of the supported xDS types.
 */
@UnstableApi
public enum XdsType {

    LISTENER("type.googleapis.com/envoy.config.listener.v3.Listener", Listener.class),
    ROUTE("type.googleapis.com/envoy.config.route.v3.RouteConfiguration", RouteConfiguration.class),
    CLUSTER("type.googleapis.com/envoy.config.cluster.v3.Cluster", Cluster.class),
    ENDPOINT("type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment",
             ClusterLoadAssignment.class),
    VIRTUAL_HOST("type.googleapis.com/envoy.config.route.v3.VirtualHost", VirtualHost.class),
    SECRET("type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.Secret", Secret.class);

    private static final Set<XdsType> discoverableTypes =
            EnumSet.of(LISTENER, ROUTE, CLUSTER, ENDPOINT, SECRET);

    private final String typeUrl;
    private final Class<? extends GeneratedMessageV3> resourceClass;

    XdsType(String typeUrl, Class<? extends GeneratedMessageV3> resourceClass) {
        this.typeUrl = typeUrl;
        this.resourceClass = resourceClass;
    }

    /**
     * Returns the url of the xDS type.
     */
    public String typeUrl() {
        return typeUrl;
    }

    /**
     * Returns the protobuf class for this xDS type.
     */
    public Class<? extends GeneratedMessageV3> resourceClass() {
        return resourceClass;
    }

    static Set<XdsType> discoverableTypes() {
        return discoverableTypes;
    }
}
