/*
 * Copyright 2023 LINE Corporation
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

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

interface ListenerNodeProcessor extends BaseNodeProcessor {

    default void process(ListenerResourceHolder holder) {
        final HttpConnectionManager connectionManager = holder.connectionManager();
        if (connectionManager == null) {
            return;
        }
        if (connectionManager.hasRouteConfig()) {
            final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
            safeCloseables().add(xdsBootstrap().addStaticNode(XdsType.ROUTE.typeUrl(),
                                                              routeConfig.getName(), routeConfig));
        } else if (connectionManager.hasRds()) {
            final Rds rds = connectionManager.getRds();
            final String routeName = rds.getRouteConfigName();
            final ConfigSource configSource = rds.getConfigSource();
            safeCloseables().add(xdsBootstrap().subscribe(configSource, XdsType.ROUTE, routeName));
        } else {
            throw new IllegalArgumentException("ConnectionManager should have a rds or RouteConfig for (" +
                                               connectionManager + ')');
        }
    }
}
