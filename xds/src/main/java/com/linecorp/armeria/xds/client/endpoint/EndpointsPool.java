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
import static com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys.DEGRADED_ATTR;
import static com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys.HEALTHY_ATTR;

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
import com.linecorp.armeria.common.AttributesBuilder;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.netty.util.concurrent.EventExecutor;

final class EndpointsPool implements AsyncCloseable {

    private EndpointGroup delegate = EndpointGroup.of();
    private Map<Endpoint, Attributes> endpointAttrs = ImmutableMap.of();
    private final EventExecutor eventExecutor;
    private Consumer<List<Endpoint>> listener = ignored -> {};

    EndpointsPool(EventExecutor eventExecutor) {
        this.eventExecutor = eventExecutor;
    }

    void updateClusterSnapshot(ClusterSnapshot newSnapshot, Consumer<List<Endpoint>> endpointsListener) {
        // it is very important that the listener is removed first so that endpoints aren't deemed
        // unhealthy due to closing a HealthCheckedEndpointGroup
        delegate.removeListener(listener);
        delegate.closeAsync();

        // set the new endpoint and listener
        delegate = XdsEndpointUtil.convertEndpointGroup(newSnapshot, endpointAttrs);
        listener = endpoints -> eventExecutor.execute(
                () -> endpointsListener.accept(cacheAttributesAndDelegate(endpoints)));
        delegate.addListener(listener, true);
    }

    private List<Endpoint> cacheAttributesAndDelegate(List<Endpoint> endpoints) {
        final long defaultTimestamp = System.nanoTime();
        final ImmutableMap.Builder<Endpoint, Attributes> endpoint2AttrsBuilder = ImmutableMap.builder();
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        for (Endpoint endpoint: endpoints) {
            final Attributes prevAttrs = endpointAttrs.getOrDefault(endpoint, Attributes.of());
            AttributesBuilder attrsBuilder = prevAttrs.toBuilder();
            if (endpoint.attr(CREATED_AT_NANOS_KEY) == null && !prevAttrs.hasAttr(CREATED_AT_NANOS_KEY)) {
                attrsBuilder = attrsBuilder.set(CREATED_AT_NANOS_KEY, defaultTimestamp);
            }
            if (endpoint.attr(HEALTHY_ATTR) == null && !prevAttrs.hasAttr(HEALTHY_ATTR)) {
                attrsBuilder = attrsBuilder.set(HEALTHY_ATTR, true);
            }
            if (endpoint.attr(DEGRADED_ATTR) == null && !prevAttrs.hasAttr(DEGRADED_ATTR)) {
                attrsBuilder = attrsBuilder.set(DEGRADED_ATTR, false);
            }
            final Attributes attrs = attrsBuilder.build();
            endpoint = endpoint.withAttrs(attrs);
            endpoint2AttrsBuilder.put(endpoint, attrs);
            endpointsBuilder.add(endpoint);
        }
        endpointAttrs = endpoint2AttrsBuilder.buildKeepingLast();
        return endpointsBuilder.build();
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
