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

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class EndpointResourceParser extends ResourceParser<ClusterLoadAssignment, EndpointXdsResource> {

    static final EndpointResourceParser INSTANCE = new EndpointResourceParser();

    private EndpointResourceParser() {}

    @Override
    EndpointXdsResource parse(ClusterLoadAssignment message) {
        return new EndpointXdsResource(message);
    }

    @Override
    String name(ClusterLoadAssignment message) {
        return message.getClusterName();
    }

    @Override
    Class<ClusterLoadAssignment> clazz() {
        return ClusterLoadAssignment.class;
    }

    @Override
    boolean isFullStateOfTheWorld() {
        return false;
    }

    @Override
    XdsType type() {
        return XdsType.ENDPOINT;
    }
}
