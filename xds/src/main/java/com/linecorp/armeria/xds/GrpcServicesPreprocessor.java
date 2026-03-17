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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;

final class GrpcServicesPreprocessor implements HttpPreprocessor {

    private final List<GrpcService> services;
    private final BootstrapClusters bootstrapClusters;

    GrpcServicesPreprocessor(List<GrpcService> services, BootstrapClusters bootstrapClusters) {
        this.services = services;
        this.bootstrapClusters = bootstrapClusters;
        for (GrpcService service : services) {
            final CompletableFuture<?> f =
                    bootstrapClusters.snapshotFuture(service.getEnvoyGrpc().getClusterName());
            checkState(f != null,
                       "Cannot find cluster for '%s' in bootstrap clusters '%s'", service, bootstrapClusters);
        }
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

        final CompletableFuture<ClusterSnapshot> snapshotFuture =
                bootstrapClusters.snapshotFuture(clusterName);
        checkArgument(snapshotFuture != null, "No cluster found for name: %s", clusterName);
        return HttpResponse.of(snapshotFuture.thenApply(snapshot -> {
            final XdsLoadBalancer loadBalancer = snapshot.loadBalancer();
            checkArgument(loadBalancer != null, "No endpoints found for name: %s", clusterName);
            final Endpoint endpoint = loadBalancer.selectNow(ctx);
            checkArgument(endpoint != null, "Endpoint not selected found for name: %s", clusterName);
            XdsCommonUtil.setTlsParams(ctx, endpoint);
            ctx.setEndpointGroup(endpoint);
            try {
                return delegate.execute(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }
}
