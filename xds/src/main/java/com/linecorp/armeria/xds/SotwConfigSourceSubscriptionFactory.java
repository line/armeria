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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.netty.util.concurrent.EventExecutor;

/**
 * A factory that creates a {@link ConfigSourceSubscription} for a non-gRPC config source.
 * Implementations are resolved by name or by the {@code custom_config_source} type URL
 * via the extension registry.
 *
 * <h2>How the pieces fit together</h2>
 * <ol>
 *   <li>Armeria calls {@link #create} with a {@link ConfigSource}, a
 *       {@link SotwSubscriptionCallbacks}, and an {@link EventExecutor}.</li>
 *   <li>The returned {@link ConfigSourceSubscription} watches the external source
 *       (file, KV store, etc.) and calls
 *       {@link SotwSubscriptionCallbacks#onDiscoveryResponse} whenever new data arrives.</li>
 *   <li>Armeria calls {@link ConfigSourceSubscription#updateInterests} when the set of
 *       watched resource names changes, and {@link ConfigSourceSubscription#close()} on shutdown.</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class MyConfigSourceFactory implements SotwConfigSourceSubscriptionFactory {
 *
 *     @Override
 *     public String name() {
 *         return "my-config-source";
 *     }
 *
 *     @Override
 *     public ConfigSourceSubscription create(ConfigSource configSource,
 *                                            SotwSubscriptionCallbacks callbacks,
 *                                            EventExecutor eventLoop) {
 *         return new ConfigSourceSubscription() {
 *             // Start watching the external source and deliver updates:
 *             //   callbacks.onDiscoveryResponse(response);
 *
 *             @Override
 *             public void updateInterests(XdsType type, Set<String> resourceNames) {
 *                 // Optionally adjust what this subscription fetches.
 *             }
 *
 *             @Override
 *             public void close() {
 *                 // Clean up resources (close connections, cancel watchers, etc.).
 *             }
 *         };
 *     }
 * }
 * }</pre>
 *
 * <p>Custom implementations can be registered via {@link java.util.ServiceLoader} SPI.
 */
@UnstableApi
public interface SotwConfigSourceSubscriptionFactory extends XdsExtensionFactory {

    /**
     * Creates a {@link ConfigSourceSubscription} for the given config source.
     *
     * @param configSource the full {@link ConfigSource} from xDS bootstrap or resource
     * @param callbacks the callbacks to invoke when resources are received
     * @param eventLoop the event loop for scheduling and synchronization
     * @return a new {@link ConfigSourceSubscription}
     */
    ConfigSourceSubscription create(ConfigSource configSource,
                                    SotwSubscriptionCallbacks callbacks,
                                    EventExecutor eventLoop);
}
