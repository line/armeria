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

import static com.linecorp.armeria.xds.client.endpoint.XdsAttributeKeys.ROUTE_CONFIG;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.Preprocessor;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

final class RouterFilter<I extends Request, O extends Response> implements Preprocessor<I, O> {

    @Override
    public O execute(PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception {
        final RouteConfig routeConfig = ctx.attr(ROUTE_CONFIG);
        if (routeConfig == null) {
            final UnprocessedRequestException e = UnprocessedRequestException.of(
                    new IllegalArgumentException(
                            "RouteConfig is not set for the ctx. If a new ctx has been used, " +
                            "please make sure to use ctx.newDerivedContext()."));
            ctx.cancel(e);
            throw e;
        }
        final RouteEntry selectedRoute = routeConfig.select(ctx);
        if (selectedRoute == null) {
            final UnprocessedRequestException e = UnprocessedRequestException.of(
                    new IllegalArgumentException("No route has been selected for listener '" +
                                                 routeConfig.listenerSnapshot() + '.'));
            ctx.cancel(e);
            throw e;
        }
        final ClusterSnapshot clusterSnapshot = selectedRoute.clusterSnapshot();
        if (clusterSnapshot == null) {
            final UnprocessedRequestException e = UnprocessedRequestException.of(
                    new IllegalArgumentException("No cluster is specified for selected route '" +
                                                 selectedRoute + "'."));
            ctx.cancel(e);
            throw e;
        }
        selectedRoute.applyUpstreamFilter(ctx);

        final long responseTimeoutMillis =
                XdsCommonUtil.durationToMillis(selectedRoute.route().getRoute().getTimeout(), -1);
        if (responseTimeoutMillis > 0) {
            ctx.setResponseTimeoutMillis(responseTimeoutMillis);
        }

        final XdsLoadBalancer loadBalancer = clusterSnapshot.loadBalancer();
        if (loadBalancer == null) {
            final UnprocessedRequestException e = UnprocessedRequestException.of(
                    new IllegalArgumentException("The target cluster '" + clusterSnapshot +
                                                 "' does not specify ClusterLoadAssignments."));
            ctx.cancel(e);
            throw e;
        }

        final Endpoint endpoint = loadBalancer.selectNow(ctx);
        return execute0(delegate, ctx, req, endpoint);
    }

    private O execute0(PreClient<I, O> delegate, PreClientRequestContext ctx, I req,
                       @Nullable Endpoint endpoint) throws Exception {
        if (endpoint == null) {
            final Throwable cancellationCause = ctx.cancellationCause();
            if (cancellationCause != null) {
                throw UnprocessedRequestException.of(cancellationCause);
            }
            throw UnprocessedRequestException.of(new TimeoutException("Failed to select an endpoint."));
        }
        XdsCommonUtil.setTlsParams(ctx, endpoint);
        ctx.setEndpointGroup(endpoint);
        return delegate.execute(ctx, req);
    }
}
