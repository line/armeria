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

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

/**
 * Callback interface provided to a {@link ConfigSourceSubscription} so it can feed
 * raw {@link DiscoveryResponse}s back into the xDS resource system.
 *
 * <p>Implementations are created internally by Armeria and passed to
 * {@link SotwConfigSourceSubscriptionFactory#create}. Custom config source subscriptions
 * should call {@link #onDiscoveryResponse} whenever new data is received from the
 * external source.
 *
 * @see SotwConfigSourceSubscriptionFactory
 */
@UnstableApi
public interface SotwSubscriptionCallbacks {

    /**
     * Called with a raw {@link DiscoveryResponse} from a SotW config source.
     * The implementation is responsible for resolving the resource type, parsing resources,
     * and applying the results.
     *
     * <p><strong>Threading:</strong> This method must be called from the
     * {@link io.netty.util.concurrent.EventExecutor} that was supplied to
     * {@link SotwConfigSourceSubscriptionFactory#create}. Calling it from any other
     * thread will result in an {@link IllegalArgumentException}.
     *
     * @param response the discovery response
     */
    void onDiscoveryResponse(DiscoveryResponse response);
}
