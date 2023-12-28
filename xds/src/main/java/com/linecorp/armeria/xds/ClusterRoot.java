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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A root node representing a {@link Cluster}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 * Note that it is important to close this resource to avoid leaking connections to the control plane server.
 */
public final class ClusterRoot extends AbstractNode<ClusterResourceHolder> implements SafeCloseable {

    private final XdsBootstrapImpl xdsBootstrap;
    @Nullable
    private final SafeCloseable safeCloseable;

    ClusterRoot(XdsBootstrapImpl xdsBootstrap, String resourceName, boolean autoSubscribe) {
        super(xdsBootstrap.eventLoop());
        this.xdsBootstrap = xdsBootstrap;
        if (autoSubscribe) {
            safeCloseable = xdsBootstrap.subscribe(XdsType.CLUSTER, resourceName);
        } else {
            safeCloseable = null;
        }
        xdsBootstrap.addClusterWatcher(resourceName, this);
    }

    /**
     * Returns a node representation of the {@link ClusterLoadAssignment} contained by this listener.
     */
    public EndpointNode endpointNode() {
        return new EndpointNode(xdsBootstrap, this);
    }

    @Override
    public void close() {
        if (safeCloseable != null) {
            safeCloseable.close();
        }
    }
}
