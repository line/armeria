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

package com.linecorp.armeria.xds.filter;

import java.util.Map;
import java.util.ServiceLoader;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A registry for {@link HttpFilterFactory} implementations.
 */
@UnstableApi
public final class HttpFilterFactoryRegistry {

    private static final Map<String, HttpFilterFactory<?>> factories;

    static {
        final ImmutableMap.Builder<String, HttpFilterFactory<?>> factoriesBuilder = ImmutableMap.builder();
        ServiceLoader.load(HttpFilterFactory.class).forEach(factory -> {
            factoriesBuilder.put(factory.filterName(), factory);
        });
        factories = factoriesBuilder.build();
    }

    /**
     * Returns the registered {@link HttpFilterFactory}.
     *
     * @param name the name of the filter represented by {@link HttpFilter#getName()}
     */
    @Nullable
    public static HttpFilterFactory<?> filterFactory(String name) {
        return factories.get(name);
    }

    private HttpFilterFactoryRegistry() {
    }
}
