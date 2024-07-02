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

import static com.linecorp.armeria.xds.client.endpoint.XdsEndpointUtil.convertLoadAssignment;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckedEndpointPool;
import com.linecorp.armeria.internal.client.endpoint.healthcheck.HttpHealthChecker;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RefreshRate;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

@UnstableApi
final class EndpointGroupConverter {

    private final UpdatableHealthCheckerParams healthCheckerParams =
            new UpdatableHealthCheckerParams();

    HealthCheckedEndpointPool endpointPool = new HealthCheckedEndpointPool(
            Backoff.ofDefault(), ClientOptions.of(), ctx -> {
        final HttpHealthChecker checker = new HttpHealthChecker(ctx);
        checker.start();
        return checker;
    }, healthCheckerParams);

    EndpointGroup convertEndpointGroup(ClusterSnapshot clusterSnapshot) {
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
                throw new UnsupportedOperationException(
                        "Cluster (" + cluster.getName() + ") is attempting to use an" +
                        "unsupported cluster type: (" + cluster.getType() + "). " +
                        "Only (STATIC, STRICT_DNS, EDS) are supported.");
        }
        if (!cluster.getHealthChecksList().isEmpty()) {
            // multiple health-checks aren't supported
            final HealthCheck healthCheck = cluster.getHealthChecksList().get(0);
            return maybeHealthChecked(endpointGroup, cluster, healthCheck);
        }
        return endpointGroup;
    }

    private EndpointGroup maybeHealthChecked(EndpointGroup delegate, Cluster cluster, HealthCheck healthCheck) {
        if (!healthCheck.hasHttpHealthCheck()) {
            return delegate;
        }
        final HttpHealthCheck httpHealthCheck = healthCheck.getHttpHealthCheck();
        healthCheckerParams.updateHttpHealthCheck(cluster, httpHealthCheck);
        return new XdsHealthCheckedEndpointGroup(delegate, endpointPool);
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

                // wrap in an assigning EndpointGroup to set appropriate attributes
                final EndpointGroup xdsEndpointGroup = new XdsAttributeAssigningEndpointGroup(
                        builder.build(), localityLbEndpoints, lbEndpoint);
                endpointGroupBuilder.add(xdsEndpointGroup);
            }
        }
        return EndpointGroup.of(endpointGroupBuilder.build());
    }

    static class XdsHealthCheckedEndpointGroup extends DynamicEndpointGroup
            implements Consumer<List<Endpoint>> {

        private final EndpointGroup delegate;
        private final HealthCheckedEndpointPool pool;
        private final Consumer<List<Endpoint>> delegateListener;

        XdsHealthCheckedEndpointGroup(EndpointGroup delegate, HealthCheckedEndpointPool pool) {
            this.delegate = delegate;
            this.pool = pool;
            delegateListener = pool::setEndpoints;
            delegate.addListener(delegateListener, true);
            pool.addListener(this, true);
        }

        @Override
        public void accept(List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }

        @Override
        protected void doCloseAsync(CompletableFuture<?> future) {
            delegate.removeListener(delegateListener);
            pool.removeListener(this);
            super.doCloseAsync(future);
        }
    }
}
