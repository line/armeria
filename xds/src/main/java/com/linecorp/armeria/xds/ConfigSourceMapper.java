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

    // custom_config_source is an Armeria extension (field 1000) that may not exist
    // when a different proto artifact (e.g. java-control-plane api) is used at runtime.
    private static final boolean HAS_CUSTOM_CONFIG_SOURCE;

    static {
        boolean hasCustomConfigSource;
        try {
            ConfigSource.class.getMethod("hasCustomConfigSource");
            hasCustomConfigSource = true;
        } catch (NoSuchMethodException e) {
            hasCustomConfigSource = false;
        }
        HAS_CUSTOM_CONFIG_SOURCE = hasCustomConfigSource;
    }

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
        if (hasExplicitConfigSource(configSource)) {
            return configSource;
        }
        if (configSource.hasSelf()) {
            return parentConfigSource;
        }
        return null;
    }

    @Nullable
    ConfigSource cdsConfigSource() {
        if (!hasExplicitConfigSource(bootstrapCdsConfig)) {
            return null;
        }
        return bootstrapCdsConfig;
    }

    @Nullable
    ConfigSource ldsConfigSource() {
        if (!hasExplicitConfigSource(bootstrapLdsConfig)) {
            return null;
        }
        return bootstrapLdsConfig;
    }

    private static boolean hasExplicitConfigSource(ConfigSource configSource) {
        return configSource.hasApiConfigSource() || configSource.hasAds() ||
               configSource.hasPathConfigSource() ||
               (HAS_CUSTOM_CONFIG_SOURCE && configSource.hasCustomConfigSource());
    }

    ApiConfigSource bootstrapAdsConfig() {
        return bootstrapAdsConfig;
    }
}
