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

import static com.linecorp.armeria.xds.StaticResourceUtils.staticCluster;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

/**
 * A root node representing a {@link Cluster}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 * Note that it is important to close this resource to avoid leaking connections to the control plane server.
 */
@UnstableApi
public final class ClusterRoot extends AbstractRoot<ClusterSnapshot> {

    private final ClusterResourceNode node;

    ClusterRoot(SubscriptionContext context, String resourceName) {
        super(context.eventLoop());
        final Cluster cluster = context.bootstrapClusters().cluster(resourceName);
        if (cluster != null) {
            node = staticCluster(context, resourceName, this, cluster);
        } else {
            final ConfigSource configSource =
                    context.configSourceMapper().cdsConfigSource(resourceName);
            node = new ClusterResourceNode(configSource, resourceName, context,
                                           null, this, ResourceNodeType.DYNAMIC);
            context.subscribe(node);
        }
    }

    @Override
    public void close() {
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(this::close);
            return;
        }
        node.close();
        super.close();
    }
}
