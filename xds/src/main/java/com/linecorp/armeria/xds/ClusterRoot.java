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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.SnapshotStream.Subscription;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

/**
 * A root node representing a {@link Cluster}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 * Note that it is important to close this resource to avoid leaking connections to the control plane server.
 */
@UnstableApi
public final class ClusterRoot extends AbstractRoot<ClusterSnapshot> {

    @Nullable
    private Subscription subscription;

    ClusterRoot(SubscriptionContext context, String resourceName, SnapshotWatcher<Object> defaultWatcher) {
        super(context.eventLoop(), defaultWatcher);
        eventLoop().execute(safeRunnable(
                () -> subscription = context.clusterManager().register(resourceName, context, this),
                t -> onUpdate(null, XdsResourceException.maybeWrap(XdsType.CLUSTER, resourceName, t))));
    }

    @Override
    public void close() {
        eventLoop().execute(() -> {
            if (subscription != null) {
                subscription.close();
            }
            super.close();
        });
    }
}
