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

package com.linecorp.armeria.xds.internal;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;

import com.google.protobuf.Message;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientDecorationBuilder;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.ClientPreprocessorsBuilder;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.ParsedFilterConfig;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.HttpFilterFactoryRegistry;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtil {

    private FilterUtil() {}

    static ClientPreprocessors buildDownstreamFilter(ListenerSnapshot listenerSnapshot) {
        final HttpConnectionManager connectionManager = listenerSnapshot.xdsResource().connectionManager();
        if (connectionManager == null) {
            return ClientPreprocessors.of();
        }
        final List<HttpFilter> httpFilters = connectionManager.getHttpFiltersList();
        final ClientPreprocessorsBuilder builder = ClientPreprocessors.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final XdsFilter xdsFilter = xdsHttpFilter(httpFilter, null);
            if (xdsFilter == null) {
                continue;
            }
            if (xdsFilter.filterConfig().disabled()) {
                continue;
            }
            builder.add(xdsFilter.httpPreprocessor());
            builder.addRpc(xdsFilter.rpcPreprocessor());
        }
        return builder.build();
    }

    static ClientDecoration buildUpstreamFilter(ConfigSupplier configSupplier) {
        final ListenerSnapshot listenerSnapshot = configSupplier.listenerSnapshot();
        final Router router = listenerSnapshot.xdsResource().router();
        if (router == null) {
            return ClientDecoration.of();
        }
        final List<HttpFilter> httpFilters = router.getUpstreamHttpFiltersList();
        final ClientDecorationBuilder builder = ClientDecoration.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final XdsFilter xdsFilter = xdsHttpFilter(httpFilter, configSupplier);
            if (xdsFilter == null) {
                continue;
            }
            if (xdsFilter.filterConfig().disabled()) {
                continue;
            }
            builder.add(xdsFilter.httpDecorator());
            builder.addRpc(xdsFilter.rpcDecorator());
        }
        return builder.build();
    }

    @Nullable
    private static XdsFilter xdsHttpFilter(HttpFilter httpFilter, @Nullable ConfigSupplier configSupplier) {
        final HttpFilterFactory<?> filterFactory =
                HttpFilterFactoryRegistry.of().filterFactory(httpFilter.getName());
        if (filterFactory == null) {
            if (httpFilter.getIsOptional()) {
                return null;
            }
            throw new IllegalArgumentException("Couldn't find filter factory: " + httpFilter.getName());
        }
        checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG,
                      "Only 'typed_config' is supported, but '%s' was supplied",
                      httpFilter.getConfigTypeCase());
        return new DefaultXdsFilter<>(filterFactory, httpFilter, configSupplier);
    }

    interface XdsFilter {

        ParsedFilterConfig filterConfig();

        HttpPreprocessor httpPreprocessor();

        RpcPreprocessor rpcPreprocessor();

        DecoratingHttpClientFunction httpDecorator();

        DecoratingRpcClientFunction rpcDecorator();
    }

    static class DefaultXdsFilter<T extends Message> implements XdsFilter {
        private final HttpFilterFactory<T> filterFactory;
        private final T config;
        private final ParsedFilterConfig filterConfig;

        DefaultXdsFilter(HttpFilterFactory<T> filterFactory, HttpFilter httpFilter,
                         @Nullable ConfigSupplier configSupplier) {
            this.filterFactory = filterFactory;
            filterConfig = computeFinalConfig(filterFactory, httpFilter, configSupplier);
            config = filterConfig.config(filterFactory);
        }

        private ParsedFilterConfig computeFinalConfig(HttpFilterFactory<T> filterFactory, HttpFilter httpFilter,
                                                      @Nullable ConfigSupplier configSupplier) {
            if (configSupplier != null) {
                final ParsedFilterConfig config = configSupplier.config(filterFactory.filterName());
                if (config != null) {
                    return config;
                }
            }

            return ParsedFilterConfig.of(httpFilter.getName(), httpFilter.getTypedConfig(),
                                         httpFilter.getIsOptional(), httpFilter.getDisabled());
        }

        @Override
        public ParsedFilterConfig filterConfig() {
            return filterConfig;
        }

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return filterFactory.httpPreprocessor(config);
        }

        @Override
        public RpcPreprocessor rpcPreprocessor() {
            return filterFactory.rpcPreprocessor(config);
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return filterFactory.httpDecorator(config);
        }

        @Override
        public DecoratingRpcClientFunction rpcDecorator() {
            return filterFactory.rpcDecorator(config);
        }
    }
}
