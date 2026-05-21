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

package com.linecorp.armeria.xds.configsource;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsExtensionFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

/**
 * A factory that creates a {@link SnapshotStream} of {@link DiscoveryResponse}s
 * for a non-gRPC config source. Implementations are resolved by name or by the
 * {@code custom_config_source} type URL via the extension registry.
 *
 * <h2>How the pieces fit together</h2>
 * <ol>
 *   <li>Armeria calls {@link #create} with a {@link ConfigSource}, a {@link FactoryContext},
 *       and a {@link SnapshotStream} of {@link InterestedResources}.</li>
 *   <li>The returned {@link SnapshotStream} watches the external source
 *       (file, KV store, etc.) and emits {@link DiscoveryResponse}s to subscribers.</li>
 *   <li>Armeria subscribes to the stream and handles resource parsing, storage, and
 *       subscriber notification internally.</li>
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
 *     public SnapshotStream<DiscoveryResponse> create(
 *             ConfigSource configSource,
 *             FactoryContext factoryContext,
 *             SnapshotStream<InterestedResources> interestedResources) {
 *         return new RefCountedStream<DiscoveryResponse>() {
 *             @Override
 *             protected Subscription onStart(SnapshotWatcher<DiscoveryResponse> watcher) {
 *                 // Start watching the external source and call emit(response, null)
 *                 // whenever new data arrives.
 *                 ...
 *                 return () -> {
 *                     // Clean up resources (close connections, cancel watchers, etc.).
 *                 };
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
     * Creates a {@link SnapshotStream} of {@link DiscoveryResponse}s for the given config source.
     *
     * @param configSource the full {@link ConfigSource} from xDS bootstrap or resource
     * @param factoryContext provides runtime infrastructure such as the event loop and metrics
     * @param interestedResources a stream that emits the currently subscribed resource names
     *                            whenever subscriptions change
     * @return a new {@link SnapshotStream}
     */
    SnapshotStream<DiscoveryResponse> create(ConfigSource configSource,
                                             FactoryContext factoryContext,
                                             SnapshotStream<InterestedResources> interestedResources);
}
