/*
 * Copyright 2025 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.ClusterManager;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.netty.util.concurrent.EventExecutor;

/**
 * (Advanced users only) Represents a {@link ClusterManager}. Manages the currently active {@link Cluster}s
 * and their corresponding {@link EndpointGroup}s.
 * Users are expected to register a cluster, and then specify a {@link ClusterSnapshot}
 * to retrieve a corresponding {@link XdsLoadBalancer}.
 * <pre>{@code
 * XdsClusterManager cm = XdsClusterManager.of(...);
 * cm.register("clusterA");
 * XdsLoadBalancer lb = cm.update(clusterSnapshot);
 * cm.unregister("clusterA");
 * }</pre>
 * It is advised to use {@link XdsPreprocessor} instead of using this implementation directly.
 */
@UnstableApi
public interface XdsClusterManager extends SafeCloseable {

    /**
     * Creates a {@link XdsClusterManager}.
     */
    static XdsClusterManager of(EventExecutor eventLoop, Bootstrap bootstrap) {
        return new DefaultXdsClusterManager(eventLoop, bootstrap);
    }

    /**
     * Registers a {@link Cluster} with the specified name. Users are expected to register
     * a name before calling {@link #update(String, ClusterSnapshot)}.
     */
    void register(String name);

    /**
     * Gets the {@link XdsLoadBalancer} registered with the specified name if exists.
     */
    @Nullable
    XdsLoadBalancer get(String name);

    /**
     * Updates the cluster manager with the specified {@link ClusterSnapshot} and
     * returns the corresponding {@link XdsLoadBalancer}.
     */
    XdsLoadBalancer update(String name, ClusterSnapshot snapshot);

    /**
     * Unregisters the {@link Cluster} with the specified name. This should be called
     * for each {@link #register(String)} call to ensure unused resources are cleaned up.
     */
    void unregister(String name);
}
