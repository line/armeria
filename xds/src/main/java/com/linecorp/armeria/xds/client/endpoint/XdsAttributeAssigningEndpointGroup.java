/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.XdsAttributeKeys.LB_ENDPOINT_KEY;
import static com.linecorp.armeria.xds.client.endpoint.XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

final class XdsAttributeAssigningEndpointGroup extends DynamicEndpointGroup
        implements Consumer<List<Endpoint>> {

    private final LocalityLbEndpoints localityLbEndpoints;
    private final LbEndpoint lbEndpoint;
    private final EndpointGroup delegate;
    final TransportSocketSnapshot matched;

    XdsAttributeAssigningEndpointGroup(EndpointGroup delegate, LocalityLbEndpoints localityLbEndpoints,
                                       LbEndpoint lbEndpoint,
                                       TransportSocketSnapshot transportSocket,
                                       List<TransportSocketMatchSnapshot> transportSocketMatches) {
        this.localityLbEndpoints = localityLbEndpoints;
        this.lbEndpoint = lbEndpoint;
        matched = TransportSocketMatchUtil.selectTransportSocket(transportSocket, transportSocketMatches,
                                                                 lbEndpoint, localityLbEndpoints);
        this.delegate = delegate;
        delegate.addListener(this, true);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        final List<Endpoint> mappedEndpoints =
                endpoints.stream()
                         .map(endpoint ->
                                      endpoint.withAttr(LB_ENDPOINT_KEY, lbEndpoint)
                                              .withAttr(LOCALITY_LB_ENDPOINTS_KEY, localityLbEndpoints)
                                              .withAttr(XdsCommonUtil.TRANSPORT_SOCKET_SNAPSHOT_KEY, matched)
                                              .withWeight(XdsEndpointUtil.endpointWeight(lbEndpoint)))
                         .collect(Collectors.toList());
        setEndpoints(mappedEndpoints);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        delegate.closeAsync().handle((ignored, t) -> future.complete(null));
    }
}
