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

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A resource node representing a {@link ClusterLoadAssignment}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 */
public final class EndpointNode extends AbstractNode<EndpointResourceHolder> {

    @Nullable
    private String currentName;

    EndpointNode(XdsBootstrapImpl xdsBootstrap, AbstractNode<ClusterResourceHolder> clusterConfig) {
        super(xdsBootstrap.eventLoop());
        clusterConfig.addListener(new ResourceWatcher<ClusterResourceHolder>() {
            @Override
            public void onChanged(ClusterResourceHolder update) {
                final Cluster cluster = update.data();
                final String clusterName = cluster.getName();
                if (Objects.equals(clusterName, currentName)) {
                    return;
                }
                if (currentName != null) {
                    xdsBootstrap.removeEndpointWatcher(currentName, EndpointNode.this);
                }
                xdsBootstrap.addEndpointWatcher(clusterName, EndpointNode.this);
                currentName = clusterName;
            }
        });
    }
}
