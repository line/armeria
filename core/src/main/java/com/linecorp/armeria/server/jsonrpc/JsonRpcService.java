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
package com.linecorp.armeria.server.jsonrpc;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * Represents an {@link HttpServiceWithRoutes} that is specialized
 * for handling and dispatching JSON-RPC requests.
 * Implementations of this interface typically act as a central point for routing incoming JSON-RPC calls
 * to their respective service methods or handlers.
 * <p>
 * Instances of {@link JsonRpcService} are usually configured and created using a {@link JsonRpcServiceBuilder},
 * obtainable via the static factory method {@link #builder(ServerBuilder)}.
 * This service integrates with Armeria's routing and service infrastructure, allowing JSON-RPC
 * endpoints to be defined alongside other HTTP services.
 * </p>
 */
public interface JsonRpcService extends HttpServiceWithRoutes {

    /**
     * Returns a new {@link JsonRpcServiceBuilder} to configure and create a {@link JsonRpcService}.
     * The builder allows for the registration of both annotated service objects and direct {@link HttpService}
     * instances to handle different JSON-RPC methods under specified path prefixes.
     *
     * @param sb the {@link ServerBuilder} with which this JSON-RPC service and its associated annotated
     *           services will be registered. This is necessary for integrating annotated JSON-RPC methods
     *           into the server's routing and lifecycle. Must not be {@code null}.
     * @return a new {@link JsonRpcServiceBuilder} instance, ready for configuration.
     */
    static JsonRpcServiceBuilder builder(ServerBuilder sb) {
        return new JsonRpcServiceBuilder(sb);
    }

    /**
     * {@inheritDoc}
     * <p>
     * For {@link JsonRpcService} implementations, path caching is enabled by default (returns {@code true}).
     * JSON-RPC services use distinct paths for different methods.
     * </p>
     * @return {@code true} by default, indicating that the path resolution for this route should be cached.
     */
    @Override
    default boolean shouldCachePath(String path, @Nullable String query, Route route) {
        return true;
    }
}
