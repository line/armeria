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

package com.linecorp.armeria.xds;

import java.util.Map;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

interface ConfigSourceLifecycleObserver extends SafeCloseable {

    default void requestSent(DiscoveryRequest request) {}

    default void responseReceived(DiscoveryResponse value) {}

    default void streamOpened() {}

    default void streamError(Throwable throwable) {}

    default void streamCompleted() {}

    default void resourceUpdated(XdsType type, DiscoveryResponse response,
                                 Map<String, Object> updatedResources) {}

    default void resourceRejected(XdsType type, DiscoveryResponse response,
                                  Map<String, Throwable> rejectedResources) {}

    @Override
    default void close() {
    }
}
