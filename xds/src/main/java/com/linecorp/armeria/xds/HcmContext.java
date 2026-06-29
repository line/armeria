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

package com.linecorp.armeria.xds;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.xds.client.endpoint.RouterFilterFactory;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * Holds the parsed {@link HttpConnectionManager} and the associated filter caching streams.
 * Created once per filter chain in {@link ListenerFilterChainFactory} and shared with
 * {@link RouteStream} so that HCM-level and per-route server filter subscriptions use the
 * same underlying cache.
 */
final class HcmContext {

    private final HttpConnectionManager hcm;
    private final Function<Map<String, Any>, SnapshotStream<ClientPreprocessors>> downstream;
    private final Function<Map<String, Any>, SnapshotStream<ClientDecoration>> upstream;
    private final Function<Map<String, Any>, SnapshotStream<HttpService>> server;

    @Nullable
    static HcmContext from(FilterChain filterChain, XdsExtensionRegistry registry,
                           SubscriptionContext context) {
        final List<Filter> filters = filterChain.getFiltersList();
        if (filters.isEmpty()) {
            return null;
        }
        final Filter last = filters.get(filters.size() - 1);
        final HttpConnectionManager hcm = HttpConnectionManagerFactory.extractHcm(last, registry);
        if (hcm == null) {
            return null;
        }
        return new HcmContext(hcm, registry, context);
    }

    HcmContext(HttpConnectionManager hcm, XdsExtensionRegistry registry,
               SubscriptionContext context) {
        this.hcm = hcm;
        final List<HttpFilter> hcmHttpFilters = hcm.getHttpFiltersList();
        final Router router = findRouter(hcm, registry);
        final List<HttpFilter> upstreamFilters =
                router != null ? router.getUpstreamHttpFiltersList() : ImmutableList.of();

        downstream = SnapshotStream.caching(
                filterConfigs -> FilterUtil.buildDownstreamFilter(
                        registry, context, hcmHttpFilters, filterConfigs));
        upstream = SnapshotStream.caching(
                filterConfigs -> FilterUtil.buildUpstreamFilter(
                        registry, context, upstreamFilters, filterConfigs));
        server = SnapshotStream.caching(
                filterConfigs -> FilterUtil.buildDownstreamServerFilter(
                        registry, context, hcmHttpFilters, filterConfigs));
    }

    HttpConnectionManager hcm() {
        return hcm;
    }

    SnapshotStream<ClientPreprocessors> downstream(Map<String, Any> filterConfigs) {
        return downstream.apply(filterConfigs);
    }

    SnapshotStream<ClientDecoration> upstream(Map<String, Any> filterConfigs) {
        return upstream.apply(filterConfigs);
    }

    SnapshotStream<HttpService> server(Map<String, Any> filterConfigs) {
        return server.apply(filterConfigs);
    }

    @Nullable
    private static Router findRouter(HttpConnectionManager hcm, XdsExtensionRegistry registry) {
        final List<HttpFilter> httpFilters = hcm.getHttpFiltersList();
        if (httpFilters.isEmpty()) {
            return null;
        }
        final HttpFilter last = httpFilters.get(httpFilters.size() - 1);
        if (last.hasTypedConfig() &&
            RouterFilterFactory.extensionTypeUrl().equals(last.getTypedConfig().getTypeUrl())) {
            return registry.unpack(last.getTypedConfig(), Router.class);
        }
        if (RouterFilterFactory.extensionName().equals(last.getName())) {
            return Router.getDefaultInstance();
        }
        return null;
    }
}
