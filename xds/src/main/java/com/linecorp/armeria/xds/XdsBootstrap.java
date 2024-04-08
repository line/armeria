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
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link XdsBootstrap} encapsulates all logic to communicate with control plane servers
 * to fetch xDS resources locally. Users may choose to watch resources and listen to event
 * updates using this class. The appropriate resources are found from a {@link Bootstrap}
 * that can be provided like the following:
 * <pre>{@code
 * Bootstrap bootstrap = ...;
 * XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
 * ListenerRoot root = xdsBootstrap.listenerRoot("listener1");
 * root.addSnapshotWatcher(...);
 * root.close();
 * }</pre>
 * Initializing a {@link XdsBootstrap} does not consume any resources until a resource is subscribed
 * via {@link #listenerRoot(String)} or its variants.
 * Note that it is important to close the {@link ListenerRoot} or {@link ClusterRoot}
 * after usage to avoid leaking resources.
 * Closing the {@link XdsBootstrap} will also close all connections and relevant resources.
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
     * Constructs a {@link XdsBootstrap} which watches resources using the provided
     * {@link Bootstrap}.
     */
    @UnstableApi
    static XdsBootstrap of(Bootstrap bootstrap, EventExecutor eventLoop) {
        return new XdsBootstrapImpl(bootstrap, eventLoop);
    }

    /**
     * Represents a {@link Listener} root node of a bootstrap.
     * Users may hook watchers to the root node to listen to events.
     */
    ListenerRoot listenerRoot(String resourceName);

    /**
     * Represents a {@link Cluster} root node of a bootstrap.
     * Users may hook watchers to the root node to listen to events.
     */
    ClusterRoot clusterRoot(String resourceName);

    /**
     * Returns the event loop used to notify events.
     */
    EventExecutor eventLoop();
}
