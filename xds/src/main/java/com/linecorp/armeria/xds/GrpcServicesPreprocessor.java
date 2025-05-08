/*
 * Copyright 2025 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.netty.channel.EventLoop;

final class GrpcServicesPreprocessor implements HttpPreprocessor {

    private final List<GrpcService> services;
    private final BootstrapClusters bootstrapClusters;

    GrpcServicesPreprocessor(List<GrpcService> services, BootstrapClusters bootstrapClusters) {
        this.services = services;
        this.bootstrapClusters = bootstrapClusters;
    }

    @Override
    public HttpResponse execute(PreClient<HttpRequest, HttpResponse> delegate, PreClientRequestContext ctx,
                                HttpRequest req) throws Exception {
        // Just use the first service for now until RequestOptions.attr can be specified for grpc services
        final GrpcService grpcService = services.get(0);
        for (HeaderValue headerValue: grpcService.getInitialMetadataList()) {
            ctx.addAdditionalRequestHeader(headerValue.getKey(), headerValue.getValue());
        }
        final EnvoyGrpc envoyGrpc = grpcService.getEnvoyGrpc();
        final String clusterName = envoyGrpc.getClusterName();

        final ClusterSnapshot clusterSnapshot = bootstrapClusters.clusterSnapshot(clusterName);
        assert clusterSnapshot != null;
        final UpstreamTlsContext tlsContext = clusterSnapshot.xdsResource().upstreamTlsContext();
        if (tlsContext != null) {
            ctx.setSessionProtocol(SessionProtocol.HTTPS);
        } else {
            ctx.setSessionProtocol(SessionProtocol.HTTP);
        }

        final XdsLoadBalancer loadBalancer = bootstrapClusters.loadBalancer(clusterName);
        checkState(loadBalancer != null,
                   "Bootstrap static cluster '%s' has not specified endpoints.", clusterName);
        final Endpoint endpoint = loadBalancer.selectNow(ctx);
        if (endpoint != null) {
            ctx.setEndpointGroup(endpoint);
            return delegate.execute(ctx, req);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<HttpResponse> cf =
                loadBalancer.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                            .thenApply(endpoint0 -> {
                                ctx.setEndpointGroup(endpoint0);
                                try {
                                    return delegate.execute(ctx, req);
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            });
        return HttpResponse.of(cf);
    }
}
