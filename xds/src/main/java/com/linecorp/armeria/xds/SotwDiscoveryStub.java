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

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;

import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceStub;
import io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceStub;
import io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceStub;
import io.grpc.stub.StreamObserver;

@FunctionalInterface
interface SotwDiscoveryStub {

    StreamObserver<DiscoveryRequest> stream(StreamObserver<DiscoveryResponse> responseObserver);

    static SotwDiscoveryStub ads(GrpcClientBuilder builder) {
        final AggregatedDiscoveryServiceStub stub = builder.build(AggregatedDiscoveryServiceStub.class);
        return stub::streamAggregatedResources;
    }

    static SotwDiscoveryStub basic(XdsType type, GrpcClientBuilder builder) {
        switch (type) {
            case LISTENER:
                final ListenerDiscoveryServiceStub listenerStub =
                        builder.build(ListenerDiscoveryServiceStub.class);
                return listenerStub::streamListeners;
            case ROUTE:
                final RouteDiscoveryServiceStub routeStub =
                        builder.build(RouteDiscoveryServiceStub.class);
                return routeStub::streamRoutes;
            case CLUSTER:
                final ClusterDiscoveryServiceStub clusterStub =
                        builder.build(ClusterDiscoveryServiceStub.class);
                return clusterStub::streamClusters;
            case ENDPOINT:
                final EndpointDiscoveryServiceStub endpointStub =
                        builder.build(EndpointDiscoveryServiceStub.class);
                return endpointStub::streamEndpoints;
            default:
                throw new Error("Unexpected value: " + type);
        }
    }
}
