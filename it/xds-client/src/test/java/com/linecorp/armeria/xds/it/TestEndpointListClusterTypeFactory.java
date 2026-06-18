/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.it;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A test {@link ClusterTypeFactory} that fetches an endpoint list from an HTTP server.
 * The endpoint server URI is extracted from the cluster's {@code typed_config} as a
 * {@link StringValue}. The server is expected to return a {@link ClusterLoadAssignment}
 * in JSON format at {@code /endpoints}.
 */
public final class TestEndpointListClusterTypeFactory implements ClusterTypeFactory {

    static final String NAME = "test.endpoint-list";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SnapshotStream<EndpointSnapshot> createEndpointStream(
            ClusterXdsResource clusterXdsResource,
            FactoryContext context) {
        final Any typedConfig = clusterXdsResource.resource().getClusterType().getTypedConfig();
        final String uri = context.validator().unpack(typedConfig, StringValue.class).getValue();
        return watcher -> {
            WebClient.of(uri).get("/endpoints").aggregate().handle((response, cause) -> {
                if (cause != null) {
                    watcher.onUpdate(null, cause);
                    return null;
                }
                final ClusterLoadAssignment assignment =
                        XdsResourceReader.from(response.contentUtf8(), ClusterLoadAssignment.class);
                watcher.onUpdate(EndpointSnapshot.of(assignment), null);
                return null;
            });
            return Subscription.noop();
        };
    }
}
