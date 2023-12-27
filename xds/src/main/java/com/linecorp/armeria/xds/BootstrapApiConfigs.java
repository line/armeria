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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.DynamicResources;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class BootstrapApiConfigs {

    @Nullable
    private final ConfigSource bootstrapCdsConfig;
    @Nullable
    private final ConfigSource bootstrapLdsConfig;
    @Nullable
    private final ConfigSource bootstrapAdsConfig;

    BootstrapApiConfigs(Bootstrap bootstrap) {
        if (bootstrap.hasDynamicResources()) {
            final DynamicResources dynamicResources = bootstrap.getDynamicResources();
            bootstrapCdsConfig = dynamicResources.getCdsConfig();
            if (dynamicResources.hasAdsConfig()) {
                bootstrapAdsConfig = ConfigSource.newBuilder()
                                                 .setApiConfigSource(dynamicResources.getAdsConfig())
                                                 .build();
            } else {
                bootstrapAdsConfig = null;
            }
            bootstrapLdsConfig = dynamicResources.getLdsConfig();
        } else {
            bootstrapCdsConfig = null;
            bootstrapLdsConfig = null;
            bootstrapAdsConfig = null;
        }
    }

    ConfigSource remapConfigSource(XdsType type, @Nullable ConfigSource configSource,
                                      String resourceName) {
        if (type == XdsType.LISTENER) {
            return ldsConfigSource(configSource);
        } else if (type == XdsType.ROUTE) {
            return rdsConfigSource(configSource, resourceName);
        } else if (type == XdsType.CLUSTER) {
            return cdsConfigSource(configSource, resourceName);
        } else {
            assert type == XdsType.ENDPOINT;
            return edsConfigSource(configSource, resourceName);
        }
    }

    ConfigSource edsConfigSource(@Nullable ConfigSource configSource, String resourceName) {
        if (configSource != null && configSource.hasApiConfigSource()) {
            return configSource;
        }
        if (bootstrapAdsConfig != null) {
            return bootstrapAdsConfig;
        }
        throw new IllegalArgumentException("Cannot find a EDS config source for " + resourceName);
    }

    ConfigSource cdsConfigSource(@Nullable ConfigSource configSource, String resourceName) {
        if (configSource != null && configSource.hasApiConfigSource()) {
            return configSource;
        }
        if (bootstrapCdsConfig != null && bootstrapCdsConfig.hasApiConfigSource()) {
            return bootstrapCdsConfig;
        }
        if (bootstrapAdsConfig != null) {
            return bootstrapAdsConfig;
        }
        throw new IllegalArgumentException("Cannot find a CDS config source for route: " + resourceName);
    }

    ConfigSource rdsConfigSource(@Nullable ConfigSource configSource, String resourceName) {
        if (configSource != null && configSource.hasApiConfigSource()) {
            return configSource;
        }
        if (bootstrapAdsConfig != null) {
            return bootstrapAdsConfig;
        }
        throw new IllegalArgumentException("Cannot find a RDS config source for route: " + resourceName);
    }

    ConfigSource ldsConfigSource(@Nullable ConfigSource configSource) {
        if (configSource != null && configSource.hasApiConfigSource()) {
            return configSource;
        }
        if (bootstrapLdsConfig != null && bootstrapLdsConfig.hasApiConfigSource()) {
            return bootstrapLdsConfig;
        }
        if (bootstrapAdsConfig != null) {
            return bootstrapAdsConfig;
        }
        throw new IllegalArgumentException("Cannot find a LDS config source");
    }
}
