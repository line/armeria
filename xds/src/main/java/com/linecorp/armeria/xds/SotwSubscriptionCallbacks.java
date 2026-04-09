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
 * Batch-level callback for state-of-the-world (SotW) xDS responses.
 *
 * <p>Each invocation of {@link #onDiscoveryResponse} delivers a raw {@link DiscoveryResponse}
 * which the implementation is responsible for parsing and applying.
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
     * @param response the discovery response
     */
    void onDiscoveryResponse(DiscoveryResponse response);
}
