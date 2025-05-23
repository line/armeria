/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.filter;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.internal.RouterFilterFactory;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A registry for {@link HttpFilterFactory} implementations.
 */
public final class HttpFilterFactoryRegistry {

    private static final HttpFilterFactoryRegistry INSTANCE = new HttpFilterFactoryRegistry();

    /**
     * Returns the singleton {@link HttpFilterFactoryRegistry} instance.
     */
    public static HttpFilterFactoryRegistry of() {
        return INSTANCE;
    }

    private final Map<String, HttpFilterFactory<?>> filterFactories;

    private HttpFilterFactoryRegistry() {
        filterFactories =
                ImmutableMap.<String, HttpFilterFactory<?>>builder()
                            .put(RouterFilterFactory.INSTANCE.filterName(), RouterFilterFactory.INSTANCE)
                            .buildOrThrow();
    }

    /**
     * Returns the registered {@link HttpFilterFactory}.
     *
     * @param name the name of the filter represented by {@link HttpFilter#getName()}
     */
    @Nullable
    public HttpFilterFactory<?> filterFactory(String name) {
        return filterFactories.get(name);
    }
}
