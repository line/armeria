/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A {@link XdsBootstrap} encapsulates all logic to communicate with control plane servers
 * to fetch xDS resources locally. Users may choose to watch resources and listen to event
 * updates using this class. The appropriate resources are found from a {@link Bootstrap}
 * that can be provided like the following:
 * <pre>{@code
 * Bootstrap bootstrap = ...;
 * XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
 * xdsBootstrap.subscribe(type, resourceName);
 * xdsBootstrap.addEndpointWatcher(type, resourceName, watcher).
 * }</pre>
 * Initializing a {@link XdsBootstrap} does not consume any resources until a resource is subscribed
 * via {@link #subscribe(XdsType, String)}.
 * Note that it is important to close the {@link XdsBootstrap} after usage to avoid leaking resources.
 */
@UnstableApi
public interface XdsBootstrap extends SafeCloseable {

    /**
     * Constructs a {@link XdsBootstrap} which watches resources using the provided
     * {@link Bootstrap}.
     */
    @UnstableApi
    static XdsBootstrap of(Bootstrap bootstrap) {
        return new XdsBootstrapImpl(bootstrap);
    }

    /**
     * Starts a watch on the provided type and resource. Once a watch is started, listeners hook to
     * this {@link XdsBootstrap} will start receiving updates.
     * Note that it is important that the returned {@link SafeCloseable} is called since this call
     * can potentially create a new connection to the control plane.
     */
    @UnstableApi
    SafeCloseable subscribe(XdsType type, String resourceName);

    /**
     * Adds a watcher for {@link Listener}. Note that adding a watcher does not initiate a connection
     * and just waits for update events. This can be useful if a user already knows
     * a resource exists (i.e. static resources), and would just like to receive events
     * without consuming resources.
     * Note that the watcher callbacks are invoked from an event loop so blocking calls should be avoided.
     */
    @UnstableApi
    SafeCloseable addListenerWatcher(String resourceName, ResourceWatcher<ListenerResourceHolder> watcher);

    /**
     * Adds a watcher for {@link RouteConfiguration}. Note that adding a watcher does not initiate a connection
     * and just waits for update events. This can be useful if a user already knows
     * a resource exists (i.e. static resources), and would just like to receive events
     * without consuming resources.
     * Note that the watcher callbacks are invoked from an event loop so blocking calls should be avoided.
     */
    @UnstableApi
    SafeCloseable addRouteWatcher(String resourceName, ResourceWatcher<RouteResourceHolder> watcher);

    /**
     * Adds a watcher for {@link Cluster}. Note that adding a watcher does not initiate a connection
     * and just waits for update events. This can be useful if a user already knows
     * a resource exists (i.e. static resources), and would just like to receive events
     * without consuming resources.
     * Note that the watcher callbacks are invoked from an event loop so blocking calls should be avoided.
     */
    @UnstableApi
    SafeCloseable addClusterWatcher(String resourceName, ResourceWatcher<ClusterResourceHolder> watcher);

    /**
     * Adds a watcher for {@link ClusterLoadAssignment}. Note that adding a watcher does not initiate
     * a connection and just waits for update events. This can be useful if a user already knows
     * a resource exists (i.e. static resources), and would just like to receive events
     * without consuming resources.
     * Note that the watcher callbacks are invoked from an event loop so blocking calls should be avoided.
     */
    @UnstableApi
    SafeCloseable addEndpointWatcher(String resourceName, ResourceWatcher<EndpointResourceHolder> watcher);
}
