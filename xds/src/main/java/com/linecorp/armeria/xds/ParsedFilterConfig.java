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

import static java.util.Objects.requireNonNull;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.HttpFilterFactoryRegistry;

import io.envoyproxy.envoy.config.route.v3.FilterConfig;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * Represents a {@link FilterConfig} that is pre-parsed by the global {@link HttpFilterFactory}.
 */
@UnstableApi
public final class ParsedFilterConfig {

    private static final String FILTER_CONFIG_TYPE_URL = "envoy.config.route.v3.FilterConfig";

    /**
     * Creates a {@link ParsedFilterConfig} based on the provided {@code config}.
     *
     * @param filterName the name of the {@link HttpFilter}
     * @param config the config to be parsed
     */
    static ParsedFilterConfig of(String filterName, Any config) {
        requireNonNull(config, "config");
        if (FILTER_CONFIG_TYPE_URL.equals(config.getTypeUrl())) {
            final FilterConfig filterConfig;
            filterConfig = XdsValidatorIndexRegistry.unpack(config, FilterConfig.class);
            return new ParsedFilterConfig(filterName, filterConfig.getConfig(), filterConfig.getIsOptional(),
                                          filterConfig.getDisabled());
        }
        return new ParsedFilterConfig(filterName, config, false, false);
    }

    /**
     * Creates a {@link ParsedFilterConfig} based on the provided {@code config} and flags.
     *
     * @param filterName the name of the {@link HttpFilter}
     * @param config the config to be parsed
     * @param optional true if this config is optional
     * @param disabled true if the filter corresponding to this config should be disabled
     */
    public static ParsedFilterConfig of(String filterName, Any config, boolean optional, boolean disabled) {
        requireNonNull(filterName, "name");
        requireNonNull(config, "config");
        return new ParsedFilterConfig(filterName, config, optional, disabled);
    }

    @Nullable
    private final Object parsedConfig;
    private final boolean disabled;

    private ParsedFilterConfig(String filterName, Any config, boolean optional, boolean disabled) {
        final HttpFilterFactory<?> filterFactory = HttpFilterFactoryRegistry.filterFactory(filterName);
        if (filterFactory == null) {
            if (!optional) {
                throw new IllegalArgumentException("Filter config for filter '" + filterName +
                                                   "' is not registered in HttpFilterFactoryRegistry.");
            }
        }
        if (filterFactory != null) {
            parsedConfig = maybeParseConfig(config, filterFactory.configClass());
        } else {
            parsedConfig = null;
        }
        this.disabled = disabled;
    }

    @Nullable
    private static <T extends Message> T maybeParseConfig(Any config, Class<? extends T> clazz) {
        if (config == Any.getDefaultInstance()) {
            return null;
        }
        return XdsValidatorIndexRegistry.unpack(config, clazz);
    }

    /**
     * Returns {@code true} if the filter corresponding to this config should be disabled.
     */
    public boolean disabled() {
        return disabled;
    }

    /**
     * Returns the pre-parsed configuration for the supplied {@link HttpFilterFactory}.
     * If the configuration is optional and the {@link HttpFilterFactory} is not registered in
     * the {@link HttpFilterFactoryRegistry}, the supplied {@code defaultConfig} will be returned.
     */
    @UnstableApi
    @SuppressWarnings("unchecked")
    public <T> T parsedConfig(T defaultConfig) {
        if (parsedConfig != null) {
            return (T) parsedConfig;
        }
        return defaultConfig;
    }
}
