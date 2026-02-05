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
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class ConfigSourceMapper {

    private final ConfigSource bootstrapCdsConfig;
    private final ConfigSource bootstrapLdsConfig;
    private final ApiConfigSource bootstrapAdsConfig;

    ConfigSourceMapper(Bootstrap bootstrap) {
        bootstrapCdsConfig = bootstrap.getDynamicResources().getCdsConfig();
        bootstrapLdsConfig = bootstrap.getDynamicResources().getLdsConfig();
        bootstrapAdsConfig = bootstrap.getDynamicResources().getAdsConfig();
    }

    @Nullable
    ConfigSource configSource(ConfigSource configSource, @Nullable ConfigSource parentConfigSource) {
        if (configSource.hasAds() || configSource.hasApiConfigSource()) {
            return configSource;
        }
        if (configSource.hasSelf()) {
            return parentConfigSource;
        }
        return null;
    }

    @Nullable
    ConfigSource cdsConfigSource() {
        if (!bootstrapCdsConfig.hasApiConfigSource() && !bootstrapCdsConfig.hasAds()) {
            return null;
        }
        return bootstrapCdsConfig;
    }

    @Nullable
    ConfigSource ldsConfigSource() {
        if (!bootstrapLdsConfig.hasApiConfigSource() && !bootstrapLdsConfig.hasAds()) {
            return null;
        }
        return bootstrapLdsConfig;
    }

    ApiConfigSource bootstrapAdsConfig() {
        return bootstrapAdsConfig;
    }
}
