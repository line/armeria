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

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.Preprocessor;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;

final class RouterFilter<I extends Request, O extends Response> implements Preprocessor<I, O> {

    private final Function<CompletableFuture<O>, O> futureConverter;

    RouterFilter(Function<CompletableFuture<O>, O> futureConverter) {
        this.futureConverter = futureConverter;
    }

    @Override
    public O execute(PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception {
        final RouteConfig routeConfig = ctx.attr(ROUTE_CONFIG);
        if (routeConfig == null) {
            throw UnprocessedRequestException.of(new IllegalArgumentException(
                    "RouteConfig is not set for the ctx. If a new ctx has been used, " +
                    "please make sure to use ctx.newDerivedContext()."));
        }
        final HttpRequest httpReq = ctx.request();
        final SelectedRoute selectedRoute = routeConfig.select(ctx, httpReq);
        if (selectedRoute == null) {
            throw UnprocessedRequestException.of(new IllegalArgumentException(
                    "No route has been selected for listener '" + routeConfig.listenerSnapshot() + "'."));
        }
        final ClusterSnapshot clusterSnapshot = selectedRoute.clusterSnapshot();
        if (clusterSnapshot == null) {
            throw UnprocessedRequestException.of(new IllegalArgumentException(
                    "No cluster is specified for selected route '" + selectedRoute.routeEntry() + "'."));
        }

        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt != null) {
            if (selectedRoute.rpcClient() != null) {
                ctxExt.rpcClientCustomizer(actualClient -> {
                    DelegatingRpcClient.setDelegate(ctx, actualClient);
                    return selectedRoute.rpcClient();
                });
            }
            if (selectedRoute.httpClient() != null) {
                ctxExt.httpClientCustomizer(actualClient -> {
                    DelegatingHttpClient.setDelegate(ctx, actualClient);
                    return selectedRoute.httpClient();
                });
            }
        }

        final UpstreamTlsContext tlsContext = clusterSnapshot.xdsResource().upstreamTlsContext();
        if (tlsContext != null) {
            ctx.setSessionProtocol(SessionProtocol.HTTPS);
        } else {
            ctx.setSessionProtocol(SessionProtocol.HTTP);
        }

        final XdsLoadBalancer loadBalancer = clusterSnapshot.loadBalancer();
        if (loadBalancer == null) {
            throw UnprocessedRequestException.of(new IllegalArgumentException(
                    "The target cluster '" + clusterSnapshot + "' does not specify ClusterLoadAssignments."));
        }

        final Endpoint endpoint = loadBalancer.selectNow(ctx);
        if (endpoint != null) {
            return execute0(delegate, ctx, req, endpoint);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<O> cf =
                loadBalancer.select(ctx, temporaryEventLoop, connectTimeoutMillis(ctx))
                            .thenApply(endpoint0 -> {
                                try {
                                    return execute0(delegate, ctx, req, endpoint0);
                                } catch (Exception e) {
                                    return Exceptions.throwUnsafely(e);
                                }
                            });
        return futureConverter.apply(cf);
    }

    private int connectTimeoutMillis(ClientRequestContext ctx) {
        final Integer connectTimeoutMillisBoxed =
                (Integer) ctx.options().factory().options()
                             .channelOptions().get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
        assert connectTimeoutMillisBoxed != null;
        return connectTimeoutMillisBoxed;
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
        ctx.setEndpointGroup(endpoint);
        return delegate.execute(ctx, req);
    }
}
