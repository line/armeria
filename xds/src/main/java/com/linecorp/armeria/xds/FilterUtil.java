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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientDecorationBuilder;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.ClientPreprocessorsBuilder;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.HttpFilterFactoryRegistry;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtil {

    static Map<String, ParsedFilterConfig> mergeFilterConfigs(
            Map<String, ParsedFilterConfig> filterConfigs1,
            Map<String, ParsedFilterConfig> filterConfigs2) {
        final ImmutableMap.Builder<String, ParsedFilterConfig> builder = ImmutableMap.builder();
        builder.putAll(filterConfigs1);
        builder.putAll(filterConfigs2);
        return builder.buildKeepingLast();
    }

    static Map<String, ParsedFilterConfig> toParsedFilterConfigs(Map<String, Any> filterConfigMap) {
        final ImmutableMap.Builder<String, ParsedFilterConfig> filterConfigsBuilder = ImmutableMap.builder();
        for (Entry<String, Any> e: filterConfigMap.entrySet()) {
            filterConfigsBuilder.put(e.getKey(), ParsedFilterConfig.of(e.getKey(), e.getValue()));
        }
        return filterConfigsBuilder.buildKeepingLast();
    }

    static ClientPreprocessors buildDownstreamFilter(
            @Nullable HttpConnectionManager connectionManager) {
        if (connectionManager == null) {
            return ClientPreprocessors.of();
        }
        final List<HttpFilter> httpFilters = connectionManager.getHttpFiltersList();
        final ClientPreprocessorsBuilder builder = ClientPreprocessors.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final XdsFilter<?> xdsFilter = xdsHttpFilter(httpFilter, null);
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

    static ClientDecoration buildUpstreamFilter(
            List<HttpFilter> httpFilters, Map<String, ParsedFilterConfig> filterConfigs,
            @Nullable Function<? super HttpClient, RetryingClient> retryingDecorator) {
        final ClientDecorationBuilder builder = ClientDecoration.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final ParsedFilterConfig parsedFilterConfig = filterConfigs.get(httpFilter.getName());
            final XdsFilter<?> xdsFilter = xdsHttpFilter(httpFilter, parsedFilterConfig);
            if (xdsFilter == null) {
                continue;
            }
            if (xdsFilter.filterConfig().disabled()) {
                continue;
            }
            builder.add(xdsFilter.httpDecorator());
            builder.addRpc(xdsFilter.rpcDecorator());
        }
        if (retryingDecorator != null) {
            // add the retrying decorator as the first (outermost) decorator if exists
            builder.add(retryingDecorator);
        }
        return builder.build();
    }

    @Nullable
    private static XdsFilter<?> xdsHttpFilter(HttpFilter httpFilter,
                                              @Nullable ParsedFilterConfig parsedFilterConfig) {
        final HttpFilterFactory<?> filterFactory =
                HttpFilterFactoryRegistry.filterFactory(httpFilter.getName());
        if (filterFactory == null) {
            if (httpFilter.getIsOptional()) {
                return null;
            }
            throw new IllegalArgumentException("Couldn't find filter factory: " + httpFilter.getName());
        }
        checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG ||
                      httpFilter.getConfigTypeCase() == ConfigTypeCase.CONFIGTYPE_NOT_SET,
                      "Only 'typed_config' is supported, but '%s' was supplied",
                      httpFilter.getConfigTypeCase());
        return new XdsFilter<>(filterFactory, httpFilter, parsedFilterConfig);
    }

    private static class XdsFilter<T extends Message> {

        private final HttpFilterFactory<T> filterFactory;
        private final T config;
        private final ParsedFilterConfig filterConfig;

        XdsFilter(HttpFilterFactory<T> filterFactory, HttpFilter httpFilter,
                  @Nullable ParsedFilterConfig filterConfig) {
            this.filterFactory = filterFactory;
            if (filterConfig != null) {
                this.filterConfig = filterConfig;
            } else {
                this.filterConfig = ParsedFilterConfig.of(httpFilter.getName(), httpFilter.getTypedConfig(),
                                                          httpFilter.getIsOptional(), httpFilter.getDisabled());
            }
            config = this.filterConfig.parsedConfig(filterFactory.defaultConfig());
        }

        public ParsedFilterConfig filterConfig() {
            return filterConfig;
        }

        public HttpPreprocessor httpPreprocessor() {
            return filterFactory.httpPreprocessor(config);
        }

        public RpcPreprocessor rpcPreprocessor() {
            return filterFactory.rpcPreprocessor(config);
        }

        public DecoratingHttpClientFunction httpDecorator() {
            return filterFactory.httpDecorator(config);
        }

        public DecoratingRpcClientFunction rpcDecorator() {
            return filterFactory.rpcDecorator(config);
        }
    }

    private FilterUtil() {}
}
