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

import static com.linecorp.armeria.internal.client.endpoint.RampingUpKeys.createdAtNanos;
import static com.linecorp.armeria.internal.client.endpoint.RampingUpKeys.hasCreatedAtNanos;
import static com.linecorp.armeria.internal.client.endpoint.RampingUpKeys.withCreatedAtNanos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.netty.util.concurrent.EventExecutor;

final class EndpointPool implements AsyncCloseable {

    private EndpointGroup delegate = EndpointGroup.of();
    private Map<Endpoint, Endpoint> endpoints = ImmutableMap.of();
    private final ClusterEntry clusterEntry;
    private final EventExecutor eventExecutor;
    private Consumer<List<Endpoint>> listener = ignored -> {};

    EndpointPool(ClusterEntry clusterEntry, EventExecutor eventExecutor) {
        this.clusterEntry = clusterEntry;
        this.eventExecutor = eventExecutor;
    }

    void updateClusterSnapshot(ClusterSnapshot newSnapshot) {
        // clean up the old endpoint and listener
        delegate.removeListener(listener);
        delegate.closeAsync();

        // set the new endpoint and listener
        delegate = XdsEndpointUtil.convertEndpointGroup(newSnapshot);
        listener = endpoints -> eventExecutor.execute(
                () -> attachTimestampsAndDelegate(newSnapshot, endpoints));
        delegate.addListener(listener, true);
    }

    private void attachTimestampsAndDelegate(ClusterSnapshot clusterSnapshot, List<Endpoint> endpoints) {
        final long defaultTimestamp = System.nanoTime();
        final ImmutableMap.Builder<Endpoint, Endpoint> builder = ImmutableMap.builder();
        for (Endpoint endpoint: endpoints) {
            final Endpoint endpointWithTimestamp;
            if (this.endpoints.containsKey(endpoint) && !hasCreatedAtNanos(endpoint)) {
                final Endpoint pooledEndpoint = this.endpoints.get(endpoint);
                endpointWithTimestamp = withCreatedAtNanos(endpoint, createdAtNanos(pooledEndpoint));
            } else {
                endpointWithTimestamp = withCreatedAtNanos(endpoint, defaultTimestamp);
            }
            builder.put(endpointWithTimestamp, endpointWithTimestamp);
        }
        this.endpoints = builder.buildKeepingLast();
        clusterEntry.accept(clusterSnapshot, this.endpoints.values());
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return delegate.closeAsync();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate)
                          .toString();
    }
}
