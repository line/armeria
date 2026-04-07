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

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientDecorationBuilder;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.ClientPreprocessorsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.HttpFilterFactoryRegistry;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtil {

    static Map<String, Any> mergeFilterConfigs(
            Map<String, Any> filterConfigs1,
            Map<String, Any> filterConfigs2) {
        return ImmutableMap.<String, Any>builder()
                           .putAll(filterConfigs1)
                           .putAll(filterConfigs2)
                           .buildKeepingLast();
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
            final XdsHttpFilter instance = resolveInstance(httpFilter, null);
            if (instance == null) {
                continue;
            }
            builder.add(instance.httpPreprocessor());
            builder.addRpc(instance.rpcPreprocessor());
        }
        return builder.build();
    }

    static ClientDecoration buildUpstreamFilter(
            List<HttpFilter> httpFilters, Map<String, Any> filterConfigs,
            @Nullable RetryPolicy retryPolicy) {
        final ClientDecorationBuilder builder = ClientDecoration.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final Any perRouteConfig = filterConfigs.get(httpFilter.getName());
            final XdsHttpFilter instance = resolveInstance(httpFilter, perRouteConfig);
            if (instance == null) {
                continue;
            }
            builder.add(instance.httpDecorator());
            builder.addRpc(instance.rpcDecorator());
        }
        if (retryPolicy != null) {
            // add the retrying decorator as the first (outermost) decorator if exists
            builder.add(new RetryStateFactory(retryPolicy).retryingDecorator());
        }
        return builder.build();
    }

    @Nullable
    private static XdsHttpFilter resolveInstance(
            HttpFilter httpFilter, @Nullable Any perRouteConfig) {
        final HttpFilterFactory filterFactory =
                HttpFilterFactoryRegistry.filterFactory(httpFilter.getName());
        if (filterFactory == null) {
            if (!httpFilter.getIsOptional()) {
                throw new IllegalArgumentException(
                        "Unknown HTTP filter '" + httpFilter.getName() +
                        "': no HttpFilterFactory registered. Register an SPI " +
                        "HttpFilterFactory implementation to handle this filter.");
            }
            return null;
        }
        checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG ||
                      httpFilter.getConfigTypeCase() == ConfigTypeCase.CONFIGTYPE_NOT_SET,
                      "Only 'typed_config' is supported, but '%s' was supplied",
                      httpFilter.getConfigTypeCase());
        final Any effectiveConfig =
                perRouteConfig != null ? perRouteConfig : httpFilter.getTypedConfig();
        return filterFactory.create(httpFilter, effectiveConfig);
    }

    private FilterUtil() {}
}
