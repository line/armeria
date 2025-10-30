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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;

/**
 * Event handler for xDS load balancer updates.
 */
public interface XdsLoadBalancerLifecycleObserver extends SafeCloseable {

    /**
     * Called when an update is triggered.
     */
    default void resourceUpdated(ClusterSnapshot snapshot) {}

    /**
     * Called when endpoints are updated.
     */
    default void endpointsUpdated(ClusterSnapshot snapshot, List<Endpoint> endpoints) {}

    /**
     * Called when an update is completed.
     */
    default void stateUpdated(ClusterSnapshot snapshot, LoadBalancerState state) {}

    /**
     * Called when an update is rejected.
     */
    default void stateRejected(ClusterSnapshot snapshot, List<Endpoint> endpoints, Throwable cause) {}

    @Override
    default void close() {}
}
