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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.netty.util.concurrent.EventExecutor;

final class DefaultXdsClusterManager implements XdsClusterManager {

    @GuardedBy("lock")
    private final Map<String, ClusterEntry> clusterEntries = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();
    private boolean closed;

    private final EventExecutor eventLoop;
    private final String localClusterName;
    @Nullable
    private final Locality locality;
    @Nullable
    private LocalCluster localCluster;

    DefaultXdsClusterManager(EventExecutor eventLoop, Bootstrap bootstrap) {
        this.eventLoop = eventLoop;
        localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (bootstrap.getNode().hasLocality()) {
            locality = bootstrap.getNode().getLocality();
        } else {
            locality = null;
        }
    }

    @Override
    public void register(String name) {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            clusterEntries.computeIfAbsent(name, ignored -> new ClusterEntry(eventLoop, localCluster))
                          .retain();
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Nullable
    public XdsLoadBalancer get(String name) {
        try {
            lock.lock();
            if (closed) {
                return null;
            }
            final ClusterEntry clusterEntry = clusterEntries.get(name);
            if (clusterEntry == null) {
                return null;
            }
            return clusterEntry.loadBalancer();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public XdsLoadBalancer update(String name, ClusterSnapshot snapshot) {
        try {
            lock.lock();
            checkState(!closed, "Attempted to update cluster snapshot '%s' for a closed ClusterManager.",
                       snapshot);
            final ClusterEntry clusterEntry = clusterEntries.get(name);
            checkArgument(clusterEntry != null,
                          "Cluster with name '%s' must be registered first via register.", name);
            final XdsLoadBalancer loadBalancer = clusterEntry.update(snapshot);

            if (name.equals(localClusterName) && locality != null) {
                checkState(localCluster == null,
                           "localCluster with name '%s' can only be set once", name);
                localCluster = new LocalCluster(locality, loadBalancer);
            }

            return loadBalancer;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unregister(String name) {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            final ClusterEntry clusterEntry = clusterEntries.get(name);
            assert clusterEntry != null;
            if (clusterEntry.release()) {
                clusterEntries.remove(name);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            closed = true;
            clusterEntries.values().forEach(ClusterEntry::release);
        } finally {
            lock.unlock();
        }
    }
}
