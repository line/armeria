/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.internal.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * A static immutable {@link EndpointGroup}.
 */
public final class StaticEndpointGroup implements EndpointGroup {

    public static final StaticEndpointGroup EMPTY =
            new StaticEndpointGroup(new EmptyEndpointSelectionStrategy(), ImmutableList.of());

    private final List<Endpoint> endpoints;
    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture;
    private final EndpointSelectionStrategy selectionStrategy;
    private final EndpointSelector selector;

    public StaticEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                               Iterable<Endpoint> endpoints) {
        this.endpoints = ImmutableList.copyOf(requireNonNull(endpoints, "endpoints"));
        initialEndpointsFuture = UnmodifiableFuture.completedFuture(this.endpoints);
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        selector = selectionStrategy.newSelector(this);
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public void addListener(Consumer<? super List<Endpoint>> listener, boolean notifyLatestEndpoints) {
        if (notifyLatestEndpoints) {
            // StaticEndpointGroup will notify only once when a listener is attached.
            listener.accept(endpoints);
        }
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return selector.selectNow(ctx);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return UnmodifiableFuture.completedFuture(selectNow(ctx));
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return initialEndpointsFuture;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {}

    @Override
    public String toString() {
        return StaticEndpointGroup.class.getSimpleName() + endpoints;
    }

    private static final class EmptyEndpointSelectionStrategy implements EndpointSelectionStrategy {
        @Override
        public EndpointSelector newSelector(EndpointGroup endpointGroup) {
            return EmptyEndpointSelector.INSTANCE;
        }
    }

    private static final class EmptyEndpointSelector implements EndpointSelector {

        static final EmptyEndpointSelector INSTANCE = new EmptyEndpointSelector();

        @Nullable
        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            return null;
        }

        @Override
        public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                  ScheduledExecutorService executor,
                                                  long timeoutMillis) {
            return UnmodifiableFuture.completedFuture(null);
        }
    }
}
