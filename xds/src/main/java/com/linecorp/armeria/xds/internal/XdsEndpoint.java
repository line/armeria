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

package com.linecorp.armeria.xds.internal;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.TransportSocketSnapshot;

import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.netty.util.AttributeKey;

/**
 * An endpoint bundled with the xDS locality and load balancing metadata it originated from.
 */
public final class XdsEndpoint {

    private static final AttributeKey<XdsEndpoint> ATTR_KEY =
            AttributeKey.valueOf(XdsEndpoint.class, "XDS_ENDPOINT");

    private final Endpoint endpoint;
    private final LocalityLbEndpoints localityLbEndpoints;
    private final LbEndpoint lbEndpoint;
    @Nullable
    private final TransportSocketSnapshot transportSocket;

    /**
     * Returns the {@link XdsEndpoint} attached to the given {@link Endpoint},
     * or {@code null} if none is present.
     */
    @Nullable
    public static XdsEndpoint get(Endpoint endpoint) {
        return requireNonNull(endpoint, "endpoint").attr(ATTR_KEY);
    }

    public static XdsEndpoint of(Endpoint endpoint, LocalityLbEndpoints localityLbEndpoints,
                                 LbEndpoint lbEndpoint,
                                 @Nullable TransportSocketSnapshot transportSocket) {
        return new XdsEndpoint(endpoint, localityLbEndpoints, lbEndpoint, transportSocket);
    }

    private XdsEndpoint(Endpoint endpoint, LocalityLbEndpoints localityLbEndpoints,
                        LbEndpoint lbEndpoint, @Nullable TransportSocketSnapshot transportSocket) {
        requireNonNull(endpoint, "endpoint");
        this.localityLbEndpoints = requireNonNull(localityLbEndpoints, "localityLbEndpoints");
        this.lbEndpoint = requireNonNull(lbEndpoint, "lbEndpoint");
        this.endpoint = endpoint.withWeight(endpointWeight(lbEndpoint))
                                .withAttr(ATTR_KEY, this);
        this.transportSocket = transportSocket;
    }

    private static int endpointWeight(LbEndpoint lbEndpoint) {
        return lbEndpoint.hasLoadBalancingWeight() ?
               Math.max(1, lbEndpoint.getLoadBalancingWeight().getValue()) : 1;
    }

    /**
     * Returns the resolved endpoint (address, port, weight).
     */
    public Endpoint endpoint() {
        return endpoint;
    }

    /**
     * Returns the {@link LocalityLbEndpoints} this endpoint belongs to.
     */
    public LocalityLbEndpoints localityLbEndpoints() {
        return localityLbEndpoints;
    }

    /**
     * Returns the {@link LbEndpoint} this endpoint was resolved from.
     */
    public LbEndpoint lbEndpoint() {
        return lbEndpoint;
    }

    /**
     * Returns the matched {@link TransportSocketSnapshot}, or {@code null} if not yet resolved.
     */
    @Nullable
    public TransportSocketSnapshot transportSocket() {
        return transportSocket;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof XdsEndpoint)) {
            return false;
        }
        final XdsEndpoint that = (XdsEndpoint) o;
        return endpoint.equals(that.endpoint) &&
               localityLbEndpoints.equals(that.localityLbEndpoints) &&
               lbEndpoint.equals(that.lbEndpoint) &&
               Objects.equals(transportSocket, that.transportSocket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoint, localityLbEndpoints, lbEndpoint, transportSocket);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpoint", endpoint)
                          .add("localityLbEndpoints", localityLbEndpoints)
                          .add("lbEndpoint", lbEndpoint)
                          .add("transportSocket", transportSocket)
                          .toString();
    }
}
