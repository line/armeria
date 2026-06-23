/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.armeria.server;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A plugin that encapsulates multi-concern registration into a single
 * {@link ServerBuilder#plugin(ServerPlugin)} call.
 *
 * <p>The {@link #install(ServerBuilder)} method is called during {@link Server} construction
 * and during {@link Server#reconfigure(ServerConfigurator)}, allowing the plugin to register
 * any combination of server-level concerns (e.g., ports, TLS, service decorators).
 *
 * <p>Plugins are sorted by {@link #order()} before installation. Lower values are installed first.
 *
 * <p>The {@link #close()} method is called when the {@link Server} stops, allowing the plugin
 * to clean up resources such as subscriptions or background tasks.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * Server server = Server.builder()
 *                       .http(8080)
 *                       .service("/", (ctx, req) -> HttpResponse.of(200))
 *                       .plugin(sb -> sb.decorator(LoggingService.newDecorator()))
 *                       .build();
 * }</pre>
 */
@FunctionalInterface
@UnstableApi
public interface ServerPlugin extends AutoCloseable {

    /**
     * Installs this plugin into the given {@link ServerBuilder}. Called during
     * {@link Server} construction and during {@link Server#reconfigure(ServerConfigurator)}.
     */
    void install(ServerBuilder sb);

    /**
     * Returns the order of this plugin. Plugins with lower values are installed first.
     * Plugins with the same order preserve their insertion order (stable sort).
     * The default value is {@code 0}.
     */
    default int order() {
        return 0;
    }

    /**
     * Called when the {@link Server} stops, allowing the plugin to clean up resources.
     * Note that if the same plugin instance is registered with multiple servers,
     * this method will be called once for each server that stops.
     * The default implementation is a no-op.
     */
    @Override
    default void close() {}
}
