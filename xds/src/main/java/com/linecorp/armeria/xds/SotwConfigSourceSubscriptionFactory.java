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
 * A factory that creates a {@link ConfigSourceSubscription} for a config source.
 * Implementations are resolved by name or by the {@code custom_config_source} type URL
 * via the extension registry.
 *
 * <p>Users can register custom implementations to support non-gRPC config sources
 * (e.g. CentralDogma, etcd, Consul) via {@link java.util.ServiceLoader} SPI.
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
