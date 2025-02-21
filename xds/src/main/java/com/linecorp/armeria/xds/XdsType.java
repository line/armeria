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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A representation of the supported xDS types.
 */
@UnstableApi
public enum XdsType {
    LISTENER("type.googleapis.com/envoy.config.listener.v3.Listener", true),
    ROUTE("type.googleapis.com/envoy.config.route.v3.RouteConfiguration", true),
    CLUSTER("type.googleapis.com/envoy.config.cluster.v3.Cluster", true),
    ENDPOINT("type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment", true),
    VIRTUAL_HOST("type.googleapis.com/envoy.config.route.v3.VirtualHost", false);

    private final String typeUrl;
    private final boolean discoverySupported;

    XdsType(String typeUrl, boolean discoverySupported) {
        this.typeUrl = typeUrl;
        this.discoverySupported = discoverySupported;
    }

    /**
     * Returns the url of the xDS type.
     */
    public String typeUrl() {
        return typeUrl;
    }

    boolean discoverySupported() {
        return discoverySupported;
    }
}
