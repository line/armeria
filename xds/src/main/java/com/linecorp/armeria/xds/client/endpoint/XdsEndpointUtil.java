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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RefreshRate;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.RequestMethod;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

final class XdsEndpointUtil {

    public static EndpointGroup convertEndpointGroup(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        if (endpointSnapshot == null) {
            return EndpointGroup.of();
        }
        final Cluster cluster = clusterSnapshot.xdsResource().resource();
        final EndpointGroup endpointGroup;
        switch (cluster.getType()) {
            case STATIC:
            case EDS:
                endpointGroup = staticEndpointGroup(clusterSnapshot);
                break;
            case STRICT_DNS:
                endpointGroup = strictDnsEndpointGroup(clusterSnapshot);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported cluster type: " + cluster.getType() + '.' +
                                                        "Only (STATIC, STRICT_DNS, EDS) are supported.");
        }
        if (!cluster.getHealthChecksList().isEmpty()) {
            // multiple health-checks aren't supported
            final HealthCheck healthCheck = cluster.getHealthChecksList().get(0);
            return maybeHealthChecked(endpointGroup, healthCheck);
        }
        return endpointGroup;
    }

    private static EndpointGroup maybeHealthChecked(EndpointGroup delegate, HealthCheck healthCheck) {
        if (!healthCheck.hasHttpHealthCheck()) {
            return delegate;
        }
        final HttpHealthCheck httpHealthCheck = healthCheck.getHttpHealthCheck();
        final String path = httpHealthCheck.getPath();

        // We can't support SessionProtocol, excluded endpoints,
        // per-cluster-member health checking, etc without refactoring how we deal with health checking.
        // For now, just simply health check all targets depending on the cluster configuration.
        return HealthCheckedEndpointGroup.builder(delegate, path)
                                         .useGet(healthCheckMethod(httpHealthCheck) == HttpMethod.GET)
                                         .build();
    }

    private static HttpMethod healthCheckMethod(HttpHealthCheck httpHealthCheck) {
        if (httpHealthCheck.getMethod() == RequestMethod.GET) {
            return HttpMethod.GET;
        }
        return HttpMethod.HEAD;
    }

    private static EndpointGroup staticEndpointGroup(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        assert endpointSnapshot != null;
        final List<Endpoint> endpoints = convertLoadAssignment(endpointSnapshot.xdsResource().resource());
        return EndpointGroup.of(endpoints);
    }

    private static EndpointGroup strictDnsEndpointGroup(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        assert endpointSnapshot != null;
        final Cluster cluster = clusterSnapshot.xdsResource().resource();

        final ImmutableList.Builder<EndpointGroup> endpointGroupBuilder = ImmutableList.builder();
        final ClusterLoadAssignment loadAssignment = endpointSnapshot.xdsResource().resource();
        for (LocalityLbEndpoints localityLbEndpoints: loadAssignment.getEndpointsList()) {
            for (LbEndpoint lbEndpoint: localityLbEndpoints.getLbEndpointsList()) {
                final SocketAddress socketAddress = lbEndpoint.getEndpoint().getAddress().getSocketAddress();
                final String dnsAddress = socketAddress.getAddress();
                final DnsAddressEndpointGroupBuilder builder = DnsAddressEndpointGroup.builder(dnsAddress);
                if (socketAddress.hasPortValue()) {
                    builder.port(socketAddress.getPortValue());
                }
                if (!cluster.getRespectDnsTtl()) {
                    final int refreshRateSeconds =
                            cluster.hasDnsRefreshRate() ?
                            Ints.saturatedCast(cluster.getDnsRefreshRate().getSeconds()) : 5;
                    builder.ttl(refreshRateSeconds, refreshRateSeconds);

                    if (cluster.hasDnsFailureRefreshRate()) {
                        final RefreshRate failureRefreshRate = cluster.getDnsFailureRefreshRate();
                        int baseSeconds = refreshRateSeconds;
                        int maxSeconds = refreshRateSeconds;
                        if (failureRefreshRate.hasBaseInterval()) {
                            baseSeconds = Ints.saturatedCast(failureRefreshRate.getBaseInterval().getSeconds());
                        }
                        if (failureRefreshRate.hasMaxInterval()) {
                            maxSeconds = Ints.saturatedCast(failureRefreshRate.getMaxInterval().getSeconds());
                        }
                        builder.backoff(Backoff.random(baseSeconds, maxSeconds));
                    }
                }
                // We could also use cluster.getDnsLookupFamily() after publicly opening
                // DnsAddressEndpointGroupBuilder#resolvedAddressTypes

                // wrap in an assigning EndpointGroup to set appropriate attributes
                final EndpointGroup xdsEndpointGroup = new XdsAttributeAssigningEndpointGroup(
                        builder.build(), localityLbEndpoints, lbEndpoint);
                endpointGroupBuilder.add(xdsEndpointGroup);
            }
        }
        return EndpointGroup.of(endpointGroupBuilder.build());
    }

    private static List<Endpoint> convertLoadAssignment(ClusterLoadAssignment clusterLoadAssignment) {
        return clusterLoadAssignment.getEndpointsList().stream().flatMap(
                localityLbEndpoints -> localityLbEndpoints
                        .getLbEndpointsList()
                        .stream()
                        .map(lbEndpoint -> convertToEndpoint(localityLbEndpoints, lbEndpoint)))
                                    .collect(toImmutableList());
    }

    private static Endpoint convertToEndpoint(LocalityLbEndpoints localityLbEndpoints, LbEndpoint lbEndpoint) {
        final SocketAddress socketAddress =
                lbEndpoint.getEndpoint().getAddress().getSocketAddress();
        final String hostname = lbEndpoint.getEndpoint().getHostname();
        final int weight = endpointWeight(lbEndpoint);
        final Endpoint endpoint;
        if (!Strings.isNullOrEmpty(hostname)) {
            endpoint = Endpoint.of(hostname)
                               .withIpAddr(socketAddress.getAddress())
                               .withAttr(XdsAttributesKeys.LB_ENDPOINT_KEY, lbEndpoint)
                               .withAttr(XdsAttributesKeys.LOCALITY_LB_ENDPOINTS_KEY, localityLbEndpoints);
        } else {
            endpoint = Endpoint.of(socketAddress.getAddress())
                               .withAttr(XdsAttributesKeys.LB_ENDPOINT_KEY, lbEndpoint)
                               .withAttr(XdsAttributesKeys.LOCALITY_LB_ENDPOINTS_KEY, localityLbEndpoints)
                               .withWeight(weight);
        }
        if (socketAddress.hasPortValue()) {
            return endpoint.withPort(socketAddress.getPortValue());
        }
        return endpoint;
    }

    static int endpointWeight(LbEndpoint lbEndpoint) {
        return lbEndpoint.hasLoadBalancingWeight() ?
               Math.max(1, lbEndpoint.getLoadBalancingWeight().getValue()) : 1;
    }

    private XdsEndpointUtil() {}
}
