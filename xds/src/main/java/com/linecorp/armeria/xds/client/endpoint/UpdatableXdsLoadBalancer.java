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

import javax.annotation.concurrent.NotThreadSafe;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A variant of {@link XdsLoadBalancer} which allows updates.
 * This may be useful if the cluster doesn't have a static {@link ClusterLoadAssignment}
 * and needs to be continuously updated.
 * Users are encouraged to use {@link XdsBootstrap} to retrieve an instance of {@link LoadBalancer}
 * instead of using this class directly.
 */
@UnstableApi
@NotThreadSafe
public interface UpdatableXdsLoadBalancer extends XdsLoadBalancer, SafeCloseable {

    /**
     * Updates the {@link XdsLoadBalancer} state with the input {@link ClusterSnapshot}.
     * Note that there are no thread-safety guarantees with this method.
     */
    void updateSnapshot(ClusterSnapshot clusterSnapshot);
}
