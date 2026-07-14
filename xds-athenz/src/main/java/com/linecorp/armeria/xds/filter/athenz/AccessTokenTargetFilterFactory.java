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

package com.linecorp.armeria.xds.filter.athenz;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.athenz.AthenzTokenClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.xds.athenz.AthenzFilterConfig.AccessTokenTargetConfig;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.AccessTokenTarget;

final class AccessTokenTargetFilterFactory implements HttpFilterFactory {

    private static final String NAME = "athenz.access_token_target";
    private static final String TYPE_URL =
            "type.googleapis.com/armeria.xds.athenz.AccessTokenTargetConfig";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return TYPE_URLS;
    }

    @Override
    @Nullable
    public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
        throw new UnsupportedOperationException(
                NAME + " requires reactive cluster subscription; use createStream()");
    }

    @Override
    public SnapshotStream<XdsHttpFilter> createStream(HttpFilter httpFilter, Any config,
                                                      FactoryContext context) {
        final AccessTokenTargetConfig filterConfig =
                context.validator().unpack(config, AccessTokenTargetConfig.class);
        final String ztsClusterName = filterConfig.getZtsClusterName();
        final AccessTokenTarget target = filterConfig.getAccessTokenTarget();
        if (target.getSyntaxVersion() != 1) {
            throw new IllegalArgumentException("Unsupported version: " + target.getSyntaxVersion());
        }

        return context.clusterStream(ztsClusterName).map(clusterSnapshot -> {
            final ZtsBaseClient ztsBaseClient = new XdsZtsBaseClient(clusterSnapshot.preprocessor());
            final AthenzTokenClient tokenClient =
                    AthenzTokenClient.builder(ztsBaseClient)
                                     .domainName(target.getTargetDomain())
                                     .roleNames(target.getTargetRolesList())
                                     .build();
            return new OutboundXdsHttpFilter(tokenClient);
        });
    }

    private static final class OutboundXdsHttpFilter implements XdsHttpFilter {

        private final AthenzTokenClient tokenClient;

        private OutboundXdsHttpFilter(AthenzTokenClient tokenClient) {
            this.tokenClient = tokenClient;
        }

        private static void setToken(ClientRequestContext ctx, String token) {
            ctx.setAdditionalRequestHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        }

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return (delegate, ctx, req) -> HttpResponse.of(
                    tokenClient.getToken().thenApply(token -> {
                        setToken(ctx, token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }));
        }

        @Override
        public RpcPreprocessor rpcPreprocessor() {
            return (delegate, ctx, req) -> RpcResponse.from(
                    tokenClient.getToken().thenApply(token -> {
                        setToken(ctx, token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }));
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return (delegate, ctx, req) -> HttpResponse.of(
                    tokenClient.getToken().thenApply(token -> {
                        setToken(ctx, token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }));
        }

        @Override
        public DecoratingRpcClientFunction rpcDecorator() {
            return (delegate, ctx, req) -> RpcResponse.from(
                    tokenClient.getToken().thenApply(token -> {
                        setToken(ctx, token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }));
        }
    }
}
