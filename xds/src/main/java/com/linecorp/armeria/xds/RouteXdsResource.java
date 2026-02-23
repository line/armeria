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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A resource object for a {@link RouteConfiguration}.
 */
@UnstableApi
public final class RouteXdsResource extends AbstractXdsResource {

    private final RouteConfiguration routeConfiguration;

    RouteXdsResource(RouteConfiguration routeConfiguration, String version, long revision) {
        super(version, revision);
        XdsValidatorIndexRegistry.assertValid(routeConfiguration);
        this.routeConfiguration = routeConfiguration;
    }

    RouteXdsResource(RouteConfiguration routeConfiguration) {
        this(routeConfiguration, "", 0);
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
}
