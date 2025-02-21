/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.netty.util.concurrent.EventExecutor;

final class ClusterEntry implements AsyncCloseable {

    private final Consumer<PrioritySet> localClusterEntryListener = this::updateLocalLoadBalancer;

    @Nullable
    private volatile UpdatableLoadBalancer loadBalancer;
    private boolean closed;
    @Nullable
    private PrioritySet localPrioritySet;
    @Nullable
    private final LocalCluster localCluster;
    private final EventExecutor eventExecutor;
    private int refCnt;

    ClusterEntry(EventExecutor eventExecutor, @Nullable LocalCluster localCluster) {
        this.eventExecutor = eventExecutor;
        this.localCluster = localCluster;
        if (localCluster != null) {
            localCluster.addListener(localClusterEntryListener, true);
        }
    }

    XdsLoadBalancer update(ClusterSnapshot clusterSnapshot) {
        checkState(!closed, "Cannot update cluster snapshot '%s' after closed", clusterSnapshot);
        final UpdatableLoadBalancer prevLoadBalancer = loadBalancer;
        if (prevLoadBalancer != null && Objects.equals(clusterSnapshot, prevLoadBalancer.clusterSnapshot())) {
            return prevLoadBalancer;
        }
        AttributesPool prevAttrs = AttributesPool.NOOP;
        if (prevLoadBalancer != null) {
            prevAttrs = prevLoadBalancer.attributesPool();
        }
        final UpdatableLoadBalancer updatableLoadBalancer =
                new UpdatableLoadBalancer(eventExecutor, clusterSnapshot, localCluster,
                                          localPrioritySet, prevAttrs);
        loadBalancer = updatableLoadBalancer;
        if (prevLoadBalancer != null) {
            prevLoadBalancer.close();
        }
        return updatableLoadBalancer;
    }

    private void updateLocalLoadBalancer(PrioritySet localPrioritySet) {
        if (!eventExecutor.inEventLoop()) {
            eventExecutor.execute(() -> updateLocalLoadBalancer(localPrioritySet));
            return;
        }
        this.localPrioritySet = localPrioritySet;
        final UpdatableLoadBalancer loadBalancer = this.loadBalancer;
        if (loadBalancer != null) {
            loadBalancer.updateLocalLoadBalancer(localPrioritySet);
        }
    }

    @Nullable
    UpdatableLoadBalancer loadBalancer() {
        return loadBalancer;
    }

    ClusterEntry retain() {
        refCnt++;
        return this;
    }

    boolean release() {
        refCnt--;
        assert refCnt >= 0;
        if (refCnt == 0) {
            closeAsync();
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        if (closed) {
            return UnmodifiableFuture.completedFuture(null);
        }
        closed = true;
        if (localCluster != null) {
            localCluster.removeListener(localClusterEntryListener);
        }
        final UpdatableLoadBalancer loadBalancer = this.loadBalancer;
        if (loadBalancer != null) {
            loadBalancer.close();
        }
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("loadBalancer", loadBalancer)
                          .toString();
    }
}
