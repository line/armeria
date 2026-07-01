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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientDecorationBuilder;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.ClientPreprocessorsBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtil {

    static Map<String, Any> mergeFilterConfigs(
            Map<String, Any> routeConfigFilterConfigs,
            Map<String, Any> vhostFilterConfigs,
            Map<String, Any> routeFilterConfigs) {
        return ImmutableMap.<String, Any>builder()
                           .putAll(routeConfigFilterConfigs)
                           .putAll(vhostFilterConfigs)
                           .putAll(routeFilterConfigs)
                           .buildKeepingLast();
    }

    static SnapshotStream<ClientPreprocessors> buildDownstreamFilter(
            XdsExtensionRegistry extensionRegistry, FactoryContext factoryContext,
            List<HttpFilter> httpFilters, Map<String, Any> filterConfigs) {
        if (httpFilters.isEmpty()) {
            return SnapshotStream.just(ClientPreprocessors.of());
        }
        final ImmutableList.Builder<SnapshotStream<XdsHttpFilter>> streams = ImmutableList.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final Any perRouteConfig = filterConfigs.get(httpFilter.getName());
            final SnapshotStream<XdsHttpFilter> stream =
                    resolveInstance(extensionRegistry, factoryContext, httpFilter, perRouteConfig);
            if (stream != null) {
                streams.add(stream);
            }
        }
        final ImmutableList<SnapshotStream<XdsHttpFilter>> streamList = streams.build();
        if (streamList.isEmpty()) {
            return SnapshotStream.just(ClientPreprocessors.of());
        }
        return SnapshotStream.combineNLatest(streamList).map(filters -> {
            final ClientPreprocessorsBuilder builder = ClientPreprocessors.builder();
            for (XdsHttpFilter f : filters) {
                builder.add(f.httpPreprocessor());
                builder.addRpc(f.rpcPreprocessor());
            }
            return builder.build();
        });
    }

    static SnapshotStream<ClientDecoration> buildUpstreamFilter(
            XdsExtensionRegistry extensionRegistry, FactoryContext factoryContext,
            List<HttpFilter> httpFilters, Map<String, Any> filterConfigs) {
        if (httpFilters.isEmpty()) {
            return SnapshotStream.just(ClientDecoration.of());
        }
        final ImmutableList.Builder<SnapshotStream<XdsHttpFilter>> streams = ImmutableList.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final Any perRouteConfig = filterConfigs.get(httpFilter.getName());
            final SnapshotStream<XdsHttpFilter> stream =
                    resolveInstance(extensionRegistry, factoryContext, httpFilter, perRouteConfig);
            if (stream != null) {
                streams.add(stream);
            }
        }
        final ImmutableList<SnapshotStream<XdsHttpFilter>> streamList = streams.build();
        if (streamList.isEmpty()) {
            return SnapshotStream.just(ClientDecoration.of());
        }
        return SnapshotStream.combineNLatest(streamList).map(filters -> {
            final ClientDecorationBuilder builder = ClientDecoration.builder();
            for (XdsHttpFilter f : filters) {
                builder.add(f.httpDecorator());
                builder.addRpc(f.rpcDecorator());
            }
            return builder.build();
        });
    }

    @Nullable
    static ClientDecoration buildRetryDecoration(@Nullable RetryPolicy retryPolicy) {
        if (retryPolicy == null) {
            return null;
        }
        return ClientDecoration.builder()
                               .add(new RetryStateFactory(retryPolicy).retryingDecorator())
                               .build();
    }

    private static final HttpService NO_ROUTER_SERVICE =
            (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                                          "envoy.filters.http.router filter is required " +
                                          "for server-side xDS");

    static SnapshotStream<HttpService> buildDownstreamServerFilter(
            XdsExtensionRegistry extensionRegistry, FactoryContext factoryContext,
            List<HttpFilter> httpFilters, Map<String, Any> filterConfigs) {
        if (httpFilters.isEmpty()) {
            return SnapshotStream.just(NO_ROUTER_SERVICE);
        }
        final ImmutableList.Builder<SnapshotStream<XdsHttpFilter>> streams = ImmutableList.builder();
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            final Any perRouteConfig = filterConfigs.get(httpFilter.getName());
            final SnapshotStream<XdsHttpFilter> stream =
                    resolveInstance(extensionRegistry, factoryContext, httpFilter, perRouteConfig);
            if (stream != null) {
                streams.add(stream);
            }
        }
        final ImmutableList<SnapshotStream<XdsHttpFilter>> streamList = streams.build();
        if (streamList.isEmpty()) {
            return SnapshotStream.just(NO_ROUTER_SERVICE);
        }
        return SnapshotStream.combineNLatest(streamList).map(filters -> {
            HttpService service = NO_ROUTER_SERVICE;
            for (XdsHttpFilter f : filters) {
                final DecoratingHttpServiceFunction decorator = f.serviceDecorator();
                if (decorator != null) {
                    service = service.decorate(decorator);
                }
            }
            return service;
        });
    }

    @Nullable
    static SnapshotStream<XdsHttpFilter> resolveInstance(
            XdsExtensionRegistry extensionRegistry, FactoryContext factoryContext,
            HttpFilter httpFilter, @Nullable Any perRouteConfig) {
        final Any defaultConfig = httpFilter.getTypedConfig();
        final Any filterConfig = perRouteConfig != null ? perRouteConfig : defaultConfig;
        final HttpFilterFactory factory = extensionRegistry.query(
                filterConfig, httpFilter.getName(), HttpFilterFactory.class);
        if (factory == null) {
            if (!httpFilter.getIsOptional()) {
                throw new IllegalArgumentException(
                        "Unknown HTTP filter '" + httpFilter.getName() +
                        "': no HttpFilterFactory registered. Register an " +
                        "HttpFilterFactory implementation to handle this filter.");
            }
            return null;
        }
        checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG ||
                      httpFilter.getConfigTypeCase() == ConfigTypeCase.CONFIGTYPE_NOT_SET,
                      "Only 'typed_config' is supported, but '%s' was supplied",
                      httpFilter.getConfigTypeCase());
        return factory.createStream(httpFilter, filterConfig, factoryContext)
                      .rescheduleEventsOn(factoryContext.eventLoop());
    }

    private FilterUtil() {}
}
