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

import java.util.Map;

import com.google.protobuf.Struct;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.core.v3.Locality;

/**
 * Represents the state of an xDS-based load balancer.
 */
@UnstableApi
public interface LoadBalancerState {

    /**
     * Returns the host sets mapped by priority level.
     */
    Map<Integer, HostSet> hostSets();

    /**
     * Returns whether each priority is in panic mode.
     */
    Map<Integer, Boolean> perPriorityPanic();

    /**
     * The load assigned to each priority to use healthy hosts.
     */
    Map<Integer, Integer> healthyPriorityLoad();

    /**
     * The load assigned to each priority to use degraded hosts.
     */
    Map<Integer, Integer> degradedPriorityLoad();

    /**
     * The assigned residual load percentage for each locality when zone-aware routing is used.
     */
    Map<Locality, Double> zarResidualPercentages();

    /**
     * The assigned local routing percentage when zone-aware routing is used.
     */
    double zarLocalPercentage();

    /**
     * The subset states and the corresponding metadata.
     * For load balancers which are not subset-based, this will return an empty map.
     */
    Map<Struct, LoadBalancerState> subsetStates();
}
