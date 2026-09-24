/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A {@link ClusterTypeFactory} for ORIGINAL_DST cluster types.
 * Returns an empty {@link ClusterLoadAssignment} since original-destination
 * clusters use the connection's original destination address rather than
 * discovered endpoints.
 */
final class OriginalDstClusterTypeFactory implements ClusterTypeFactory {

    static final String NAME = "armeria.cluster.original_dst";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SnapshotStream<EndpointSnapshot> createEndpointStream(
            ClusterXdsResource clusterXdsResource, FactoryContext context) {
        return SnapshotStream.just(
                EndpointSnapshot.of(ClusterLoadAssignment.getDefaultInstance()));
    }
}
