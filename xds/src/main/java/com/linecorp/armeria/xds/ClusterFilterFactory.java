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

package com.linecorp.armeria.xds;

import java.util.Set;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointGroup;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;
import com.linecorp.armeria.xds.internal.DelegatingHttpClient;
import com.linecorp.armeria.xds.internal.DelegatingRpcClient;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

/**
 * A factory which injects cluster-related filters.
 * <pre>{@code
 * [downstream preprocessors]
 *     -> [router preprocessor]
 *         -> [cluster preprocessor]
 *             -> [endpoint selection]
 *                 -> [retry decorator]
 *                     -> [cluster decorator]
 *                         -> [upstream decorators]
 * }</pre>
 */
final class ClusterFilterFactory {

    static final ClientDecoration DECORATION =
            ClientDecoration.builder()
                            .add(ClusterFilterFactory::applyHttpClusterSettings)
                            .addRpc(ClusterFilterFactory::applyRpcClusterSettings)
                            .build();

    private static final HttpClient CLUSTER_ONLY_HTTP_CLIENT =
            DECORATION.decorate(DelegatingHttpClient.of());
    private static final RpcClient CLUSTER_ONLY_RPC_CLIENT =
            DECORATION.rpcDecorate(DelegatingRpcClient.of());

    private final String clusterName;
    @Nullable
    private final XdsEndpointGroup endpointGroup;
    private final SessionProtocol sessionProtocol;

    ClusterFilterFactory(ClusterXdsResource clusterXdsResource,
                         @Nullable XdsLoadBalancer loadBalancer,
                         TransportSocketSnapshot transportSocket) {
        clusterName = clusterXdsResource.name();
        endpointGroup = loadBalancer != null ? XdsEndpointGroup.of(loadBalancer) : null;
        sessionProtocol = transportSocket.clientTlsSpec() != null ?
                          SessionProtocol.HTTPS : SessionProtocol.HTTP;
    }

    HttpPreprocessor httpPreprocessor() {
        return this::execute;
    }

    RpcPreprocessor rpcPreprocessor() {
        return this::execute;
    }

    private <I extends Request, O extends Response> O execute(
            PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception {
        if (endpointGroup == null) {
            throw UnprocessedRequestException.of(new IllegalStateException(
                    "The target cluster '" + clusterName +
                    "' does not specify ClusterLoadAssignments."));
        }
        ctx.setEndpointGroup(endpointGroup);
        ctx.setSessionProtocol(sessionProtocol);

        final RouteEntry route = ctx.attr(XdsCommonUtil.SELECTED_ROUTE);
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt != null) {
            final HttpClient httpClient = route != null ?
                                          route.httpClient() : CLUSTER_ONLY_HTTP_CLIENT;
            final RpcClient rpcClient = route != null ?
                                        route.rpcClient() : CLUSTER_ONLY_RPC_CLIENT;
            ctxExt.httpClientCustomizer(actualClient -> {
                DelegatingHttpClient.setDelegate(ctx, actualClient);
                return httpClient;
            });
            ctxExt.rpcClientCustomizer(actualClient -> {
                DelegatingRpcClient.setDelegate(ctx, actualClient);
                return rpcClient;
            });
        }
        return delegate.execute(ctx, req);
    }

    // Decorator logic — applies per-endpoint cluster settings (TLS)

    private static HttpResponse applyHttpClusterSettings(
            HttpClient delegate, ClientRequestContext ctx, HttpRequest req) throws Exception {
        applyClusterSettings(ctx);
        return delegate.execute(ctx, req);
    }

    private static RpcResponse applyRpcClusterSettings(
            RpcClient delegate, ClientRequestContext ctx, RpcRequest req) throws Exception {
        applyClusterSettings(ctx);
        return delegate.execute(ctx, req);
    }

    private static void applyClusterSettings(ClientRequestContext ctx) {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            return;
        }
        final TransportSocketSnapshot transportSocket =
                endpoint.attr(XdsCommonUtil.TRANSPORT_SOCKET_SNAPSHOT_KEY);
        if (transportSocket == null) {
            return;
        }
        @Nullable
        ClientTlsSpec clientTlsSpec = transportSocket.clientTlsSpec();
        if (clientTlsSpec == null) {
            return;
        }
        final Set<String> alpnOverride = ctx.attr(XdsCommonUtil.ALPN_OVERRIDE_KEY);
        if (alpnOverride != null && !alpnOverride.isEmpty()) {
            clientTlsSpec = clientTlsSpec.toBuilder().alpnProtocols(alpnOverride).build();
        }
        ctx.setClientTlsSpec(clientTlsSpec);
    }
}
