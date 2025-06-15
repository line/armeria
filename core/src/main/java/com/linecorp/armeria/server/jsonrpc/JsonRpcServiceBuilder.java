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

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * Builds a new {@link JsonRpcService}.
 * This service acts as a dispatcher, routing incoming JSON-RPC requests
 * based on their method name to the appropriate delegate service.
 * This class is {@link UnstableApi}
 */
@UnstableApi
public class JsonRpcServiceBuilder {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final Set<Route> routes = new HashSet<>();
    private final Map<String, HttpService> nonAnnotatedService = new LinkedHashMap<>();
    private final Map<String, Object> annotatedServices = new LinkedHashMap<>();
    private final ServerBuilder serverBuilder;

    /**
     * Creates a new {@link JsonRpcServiceBuilder} associated with the given {@link ServerBuilder}.
     * Annotated services added to this builder will be registered with this {@code serverBuilder}
     * when {@link #build()} is called.
     *
     * @param serverBuilder The {@link ServerBuilder} to which annotated services will be added.
     *                      Must not be {@code null}.
     */
    public JsonRpcServiceBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    /**
     * Adds an {@link HttpService} that handles JSON-RPC requests under the specified path prefix.
     * This method is primarily intended for internal use or advanced scenarios where direct
     * {@link HttpService} integration is required, bypassing annotation-driven setup.
     * Consider using {@link #addAnnotatedService(String, Object)} for typical use cases.
     *
     * @param prefix The path prefix (e.g., "/api/rpc") under which this service will handle requests.
     *               Must not be {@code null}.
     * @param service The {@link HttpService} to handle requests under this prefix.
     *                Must not be {@code null}.
     * @return this {@link JsonRpcServiceBuilder} for chaining.
     */
    public JsonRpcServiceBuilder addService(String prefix, HttpService service) {
        requireNonNull(prefix, "prefix");
        requireNonNull(service, "service");
        nonAnnotatedService.put(prefix, service);
        routes.add(newRoute(prefix));
        return this;
    }

    /**
     * Adds an annotated service object that handles JSON-RPC requests under the specified path prefix.
     * Methods within the provided {@code service} object, typically annotated with Armeria's
     * HTTP method annotations (e.g., {@code com.linecorp.armeria.server.annotation.Post}),
     * will be discovered and registered with the {@link ServerBuilder} when {@link #build()} is called.
     * Each registered annotated method will be automatically decorated with {@link JsonRpcServiceDecorator}.
     *
     * @param prefix The path prefix (e.g., "/user_service") under which the annotated methods of this
     *               service will be exposed. Must not be {@code null}.
     * @param service The annotated service object. Its methods will be mapped based on their annotations.
     *                Must not be {@code null}.
     * @return this {@link JsonRpcServiceBuilder} for chaining.
     */
    public JsonRpcServiceBuilder addAnnotatedService(String prefix, Object service) {
        requireNonNull(prefix, "prefix");
        requireNonNull(service, "service");
        annotatedServices.put(prefix, service);
        routes.add(newRoute(prefix));
        return this;
    }

    /**
     * Builds the final {@link JsonRpcService} (specifically, a {@link SimpleJsonRpcService}).
     * This method performs two main actions:
     * <ol>
     *   <li>Registers all annotated services
     *      (previously added via {@link #addAnnotatedService(String, Object)})
     *       with the {@link ServerBuilder} provided during construction. Each of these services is decorated
     *       with {@link JsonRpcServiceDecorator} to handle JSON-RPC specific response formatting.</li>
     *   <li>Constructs and returns a {@link SimpleJsonRpcService} instance. This service acts as the main
     *       dispatcher for incoming JSON-RPC requests, routing them to either the directly added
     *       non-annotated services or to the appropriate methods within the registered annotated services,
     *       based on the request path and method name.</li>
     * </ol>
     *
     * @return The configured {@link JsonRpcService} (an instance of {@link SimpleJsonRpcService})
     *         that handles JSON-RPC dispatching for all registered services and methods.
     */
    public JsonRpcService build() {
        for (Map.Entry<String, ?> entry : annotatedServices.entrySet()) {
            serverBuilder.annotatedService(entry.getKey(), entry.getValue());
        }
        serverBuilder.decorator(JsonRpcServiceDecorator::new);
        return new SimpleJsonRpcService(routes, mapper, nonAnnotatedService);
    }

    private Route newRoute(String prefix) {
        return Route.builder()
                .path(prefix)
                .build();
    }
}
