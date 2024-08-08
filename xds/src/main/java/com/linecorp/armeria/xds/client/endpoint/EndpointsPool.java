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

import static com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys.CREATED_AT_NANOS_KEY;
import static com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys.createdAtNanos;
import static com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys.hasCreatedAtNanos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.client.endpoint.ClusterEntry.EndpointsState;

import io.netty.util.concurrent.EventExecutor;

final class EndpointsPool implements AsyncCloseable {

    private EndpointGroup delegate = EndpointGroup.of();
    private Map<Endpoint, Attributes> prevAttrs = ImmutableMap.of();
    private final EventExecutor eventExecutor;
    private Consumer<List<Endpoint>> listener = ignored -> {};

    EndpointsPool(EventExecutor eventExecutor) {
        this.eventExecutor = eventExecutor;
    }

    void updateClusterSnapshot(ClusterSnapshot newSnapshot, Consumer<EndpointsState> endpointsListener) {
        // it is very important that the listener is removed first so that endpoints aren't deemed
        // unhealthy due to closing a HealthCheckedEndpointGroup
        delegate.removeListener(listener);
        delegate.closeAsync();

        // set the new endpoint and listener
        delegate = XdsEndpointUtil.convertEndpointGroup(newSnapshot);
        listener = endpoints -> eventExecutor.execute(
                () -> endpointsListener.accept(new EndpointsState(newSnapshot,
                                                                  cacheAttributesAndDelegate(endpoints))));
        delegate.addListener(listener, true);
    }

    private List<Endpoint> cacheAttributesAndDelegate(List<Endpoint> endpoints) {
        final long defaultTimestamp = System.nanoTime();
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        final ImmutableMap.Builder<Endpoint, Attributes> prevAttrsBuilder = ImmutableMap.builder();
        for (Endpoint endpoint: endpoints) {
            final Endpoint endpointWithTimestamp = withTimestamp(endpoint, defaultTimestamp);
            endpointsBuilder.add(endpointWithTimestamp);
            prevAttrsBuilder.put(endpoint, endpointWithTimestamp.attrs());
        }
        prevAttrs = prevAttrsBuilder.buildKeepingLast();
        return endpointsBuilder.build();
    }

    private long computeTimestamp(Endpoint endpoint, long defaultTimestamp) {
        if (hasCreatedAtNanos(endpoint)) {
            return createdAtNanos(endpoint);
        }
        Long timestamp = null;
        final Attributes prevAttr = prevAttrs.get(endpoint);
        if (prevAttr != null) {
            timestamp = prevAttr.attr(CREATED_AT_NANOS_KEY);
        }
        if (timestamp != null) {
            return timestamp;
        }
        return defaultTimestamp;
    }

    private Endpoint withTimestamp(Endpoint endpoint, long defaultTimestamp) {
        if (hasCreatedAtNanos(endpoint)) {
            return endpoint;
        }
        Long timestamp = null;
        final Attributes prevAttr = prevAttrs.get(endpoint);
        if (prevAttr != null) {
            timestamp = prevAttr.attr(CREATED_AT_NANOS_KEY);
        }
        return endpoint.withAttr(CREATED_AT_NANOS_KEY, timestamp != null ? timestamp : defaultTimestamp);
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
