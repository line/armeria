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

import static com.linecorp.armeria.xds.FilterUtil.toParsedFilterConfigs;

import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A resource object for a {@link RouteConfiguration}.
 */
@UnstableApi
public final class RouteXdsResource implements XdsResource {

    private final RouteConfiguration routeConfiguration;

    private final Map<String, ParsedFilterConfig> filterConfigs;

    RouteXdsResource(RouteConfiguration routeConfiguration) {
        this.routeConfiguration = routeConfiguration;
        filterConfigs = toParsedFilterConfigs(routeConfiguration.getTypedPerFilterConfigMap());
    }

    @Override
    public XdsType type() {
        return XdsType.ROUTE;
    }

    @Override
    public RouteConfiguration resource() {
        return routeConfiguration;
    }

    @Override
    public String name() {
        return routeConfiguration.getName();
    }

    /**
     * Returns the parsed {@link RouteConfiguration#getTypedPerFilterConfigMap()}.
     *
     * @param filterName the filter name represented by {@link HttpFilter#getName()}
     */
    @Nullable
    @UnstableApi
    public ParsedFilterConfig filterConfig(String filterName) {
        return filterConfigs.get(filterName);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final RouteXdsResource that = (RouteXdsResource) object;
        return Objects.equal(routeConfiguration, that.routeConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeConfiguration);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("routeConfiguration", routeConfiguration)
                          .toString();
    }
}
