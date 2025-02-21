/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.HttpFilterFactoryRegistry;

import io.envoyproxy.envoy.config.route.v3.FilterConfig;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * Represents a parsed {@link FilterConfig}.
 */
@UnstableApi
public final class ParsedFilterConfig {

    private static final String FILTER_CONFIG_TYPE_URL = "envoy.config.route.v3.FilterConfig";

    /**
     * Creates a {@link ParsedFilterConfig} based on the provided {@code config}.
     * If the config is of type {@link FilterConfig}, then the {@link #disabled()} flag
     * will be set accordingly.
     *
     * @param filterName the name of the {@link HttpFilter}
     * @param config the config to be parsed
     */
    public static ParsedFilterConfig of(String filterName, Any config) {
        requireNonNull(config, "config");
        if (FILTER_CONFIG_TYPE_URL.equals(config.getTypeUrl())) {
            final FilterConfig filterConfig;
            try {
                filterConfig = config.unpack(FilterConfig.class);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(
                        "Unable to unpack filter config '" + config +
                        "' to class: '" + HttpFilterFactory.class.getSimpleName() + '\'', e);
            }
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
    @Nullable
    private final Class<?> configClass;
    private final boolean disabled;

    private ParsedFilterConfig(String filterName, Any config, boolean optional, boolean disabled) {
        final HttpFilterFactory<?> filterFactory = HttpFilterFactoryRegistry.of().filterFactory(filterName);
        if (filterFactory == null) {
            if (!optional) {
                throw new IllegalArgumentException("Filter config for filter: '" + filterName +
                                                   "' is not registered in HttpFilterFactoryRegistry.");
            }
        }
        if (filterFactory != null) {
            configClass = filterFactory.configClass();
            parsedConfig = maybeParseConfig(config, filterFactory.configClass());
        } else {
            configClass = null;
            parsedConfig = null;
        }
        this.disabled = disabled;
    }

    @Nullable
    private static <T extends Message> T maybeParseConfig(@Nullable Any config, Class<? extends T> clazz) {
        if (config == null) {
            return null;
        }
        if (config == Any.getDefaultInstance()) {
            return null;
        }
        try {
            return config.unpack(clazz);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(
                    "Unable to unpack filter config '" + config + "' to class: '" +
                    clazz.getSimpleName() + '\'', e);
        }
    }

    /**
     * Returns {@code true} if the filter corresponding to this config should be disabled.
     */
    public boolean disabled() {
        return disabled;
    }

    /**
     * Returns the parsed config.
     *
     * @param filterFactory the {@link HttpFilterFactory} corresponding to this config
     */
    @SuppressWarnings("unchecked")
    public <T extends Message> T config(HttpFilterFactory<T> filterFactory) {
        if (configClass == null || parsedConfig == null) {
            return filterFactory.defaultConfig();
        }
        checkArgument(filterFactory.configClass() == configClass,
                      "Provided filter factory '%s' does not support the expected class '%s'",
                      filterFactory, configClass.getSimpleName());
        return (T) parsedConfig;
    }
}
