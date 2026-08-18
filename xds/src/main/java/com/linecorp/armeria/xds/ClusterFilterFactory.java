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

import com.google.common.base.MoreObjects;

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
import com.linecorp.armeria.xds.internal.XdsEndpoint;

import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions.ExplicitHttpConfig;

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

    private final XdsEndpointGroup endpointGroup;
    private final SessionProtocol sessionProtocol;
    @Nullable
    private final HttpProtocolOptions httpProtocolOptions;

    ClusterFilterFactory(XdsLoadBalancer loadBalancer,
                         @Nullable HttpProtocolOptions httpProtocolOptions) {
        endpointGroup = XdsEndpointGroup.of(loadBalancer);
        this.httpProtocolOptions = httpProtocolOptions;
        sessionProtocol = sessionProtocol(httpProtocolOptions);
    }

    @Nullable
    HttpProtocolOptions httpProtocolOptions() {
        return httpProtocolOptions;
    }

    HttpPreprocessor httpPreprocessor() {
        return this::execute;
    }

    RpcPreprocessor rpcPreprocessor() {
        return this::execute;
    }

    private <I extends Request, O extends Response> O execute(
            PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception {
        ctx.setEndpointGroup(endpointGroup);
        ctx.setSessionProtocol(sessionProtocol);

        final RouteCluster routeCluster = ctx.attr(XdsCommonUtil.ROUTE_CLUSTER);
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt != null) {
            final HttpClient httpClient = routeCluster != null ?
                                          routeCluster.httpClient() : CLUSTER_ONLY_HTTP_CLIENT;
            final RpcClient rpcClient = routeCluster != null ?
                                        routeCluster.rpcClient() : CLUSTER_ONLY_RPC_CLIENT;
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
        final XdsEndpoint xdsEndpoint = XdsEndpoint.get(endpoint);
        if (xdsEndpoint == null) {
            return;
        }
        final TransportSocketSnapshot transportSocket = xdsEndpoint.transportSocket();
        if (transportSocket == null) {
            return;
        }
        @Nullable
        ClientTlsSpec clientTlsSpec = transportSocket.clientTlsSpec();
        if (clientTlsSpec == null) {
            ctx.clearClientTlsSpec();
            return;
        }
        final Set<String> alpnOverride = ctx.attr(XdsCommonUtil.ALPN_OVERRIDE_KEY);
        if (alpnOverride != null && !alpnOverride.isEmpty()) {
            clientTlsSpec = clientTlsSpec.toBuilder().alpnProtocols(alpnOverride).build();
        }
        ctx.setClientTlsSpec(clientTlsSpec);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("sessionProtocol", sessionProtocol)
                          .toString();
    }

    private static SessionProtocol sessionProtocol(@Nullable HttpProtocolOptions httpProtocolOptions) {
        // we assume TLS variants for now, and switch to cleartext later if necessary
        if (httpProtocolOptions != null && httpProtocolOptions.hasExplicitHttpConfig()) {
            final ExplicitHttpConfig explicitConfig = httpProtocolOptions.getExplicitHttpConfig();
            if (explicitConfig.hasHttp2ProtocolOptions()) {
                return SessionProtocol.H2;
            }
            if (explicitConfig.hasHttpProtocolOptions()) {
                return SessionProtocol.H1;
            }
        }
        return SessionProtocol.HTTPS;
    }
}
