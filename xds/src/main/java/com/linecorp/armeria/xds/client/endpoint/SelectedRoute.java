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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.google.protobuf.Duration;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

final class SelectedRoute implements ConfigSupplier {

    private final ListenerSnapshot listenerSnapshot;
    private final RouteSnapshot routeSnapshot;
    private final VirtualHostSnapshot virtualHostSnapshot;
    private final RouteEntry routeEntry;
    @Nullable
    private final HttpClient httpClient;
    @Nullable
    private final RpcClient rpcClient;
    private final RouteEntryMatcher routeEntryMatcher;
    @Nullable
    private final Function<? super HttpClient, RetryingClient> retryingDecorator;
    private final long responseTimeoutMillis;

    SelectedRoute(ListenerSnapshot listenerSnapshot, RouteSnapshot routeSnapshot,
                  VirtualHostSnapshot virtualHostSnapshot, RouteEntry routeEntry) {
        this.listenerSnapshot = listenerSnapshot;
        this.routeSnapshot = routeSnapshot;
        this.virtualHostSnapshot = virtualHostSnapshot;
        this.routeEntry = routeEntry;
        routeEntryMatcher = new RouteEntryMatcher(routeEntry);
        final RouteAction routeAction = routeEntry.route().getRoute();
        final RetryPolicy retryPolicy = routeAction.getRetryPolicy();
        if (retryPolicy == RetryPolicy.getDefaultInstance()) {
            retryingDecorator = null;
        } else {
            retryingDecorator = new RetryStateFactory(retryPolicy).retryingDecorator();
        }
        responseTimeoutMillis = computeResponseTimeoutMillis(listenerSnapshot, routeEntry);

        final ClientDecoration upstreamFilter = FilterUtil.buildUpstreamFilter(this);
        if (upstreamFilter == ClientDecoration.of() ||
            (upstreamFilter.decorators().isEmpty() && upstreamFilter.rpcDecorators().isEmpty())) {
            httpClient = null;
            rpcClient = null;
        } else {
            httpClient = upstreamFilter.decorate(DelegatingHttpClient.INSTANCE);
            rpcClient = upstreamFilter.rpcDecorate(DelegatingRpcClient.INSTANCE);
        }
    }

    private static long computeResponseTimeoutMillis(ListenerSnapshot listenerSnapshot, RouteEntry routeEntry) {
        final long responseTimeoutMillis = computeResponseTimeoutMillis0(listenerSnapshot, routeEntry);
        if (responseTimeoutMillis == 0) {
            return Long.MAX_VALUE;
        } else {
            return responseTimeoutMillis;
        }
    }

    private static long computeResponseTimeoutMillis0(ListenerSnapshot listenerSnapshot,
                                                      RouteEntry routeEntry) {
        final HttpConnectionManager connManager = listenerSnapshot.xdsResource().connectionManager();
        if (connManager == null) {
            return -1;
        }
        final MaxStreamDuration routeMaxStreamDuration = routeEntry.route().getRoute().getMaxStreamDuration();
        final HttpProtocolOptions protocolOptions = connManager.getCommonHttpProtocolOptions();
        final long maxStreamDurationMillis = maxStreamDurationMillis(routeMaxStreamDuration, protocolOptions);
        final long timeoutMillis =
                XdsCommonUtil.durationToMillis(routeEntry.route().getRoute().getTimeout(), -1);
        if (maxStreamDurationMillis == -1) {
            return timeoutMillis;
        }
        if (timeoutMillis == -1) {
            return maxStreamDurationMillis;
        }
        return Math.min(timeoutMillis, maxStreamDurationMillis);
    }

    private static long maxStreamDurationMillis(MaxStreamDuration routeMaxStreamDuration,
                                                HttpProtocolOptions options) {
        if (routeMaxStreamDuration.getMaxStreamDuration() != Duration.getDefaultInstance()) {
            return XdsCommonUtil.durationToMillis(routeMaxStreamDuration.getMaxStreamDuration());
        }
        if (options.getMaxStreamDuration() != Duration.getDefaultInstance()) {
            return XdsCommonUtil.durationToMillis(options.getMaxStreamDuration());
        }
        return -1;
    }

    RouteEntryMatcher routeEntryMatcher() {
        return routeEntryMatcher;
    }

    @Override
    public ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    @Override
    public RouteSnapshot routeSnapshot() {
        return routeSnapshot;
    }

    @Override
    public VirtualHostSnapshot virtualHostSnapshot() {
        return virtualHostSnapshot;
    }

    @Nullable
    @Override
    public ClusterSnapshot clusterSnapshot() {
        return routeEntry.clusterSnapshot();
    }

    @Nullable
    HttpClient httpClient() {
        return httpClient;
    }

    @Nullable
    RpcClient rpcClient() {
        return rpcClient;
    }

    @Override
    public RouteEntry routeEntry() {
        return routeEntry;
    }

    @Override
    public @Nullable Function<? super HttpClient, RetryingClient> retryingDecorator() {
        return retryingDecorator;
    }

    @Override
    public long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }
}
