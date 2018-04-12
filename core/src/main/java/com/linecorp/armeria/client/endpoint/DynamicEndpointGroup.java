/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AbstractListenable;

/**
 * A dynamic {@link EndpointGroup}. The list of {@link Endpoint}s can be updated dynamically.
 */
public class DynamicEndpointGroup extends AbstractListenable<List<Endpoint>> implements EndpointGroup {
    private volatile List<Endpoint> endpoints = ImmutableList.of();
    private final Lock endpointsLock = new ReentrantLock();
    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new CompletableFuture<>();

    @Override
    public final List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * Returns the {@link CompletableFuture} which is completed when the initial {@link Endpoint}s are ready.
     */
    public CompletableFuture<List<Endpoint>> initialEndpointsFuture() {
        return initialEndpointsFuture;
    }

    /**
     * Waits until the initial {@link Endpoint}s are ready.
     */
    public List<Endpoint> awaitInitialEndpoints() {
        return initialEndpointsFuture.join();
    }

    /**
     * Adds the specified {@link Endpoint} to current {@link Endpoint} list.
     */
    protected final void addEndpoint(Endpoint e) {
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            final ImmutableList.Builder<Endpoint> newEndpointsBuilder = ImmutableList.builder();
            newEndpointsBuilder.addAll(endpoints);
            newEndpointsBuilder.add(e);
            endpoints = newEndpoints = newEndpointsBuilder.build();
        } finally {
            endpointsLock.unlock();
        }

        notifyListeners(newEndpoints);
        completeInitialEndpointsFuture(newEndpoints);
    }

    /**
     * Removes the specified {@link Endpoint} from current {@link Endpoint} list.
     */
    protected final void removeEndpoint(Endpoint e) {
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            endpoints = newEndpoints = endpoints.stream()
                                                .filter(endpoint -> !endpoint.equals(e))
                                                .collect(toImmutableList());
        } finally {
            endpointsLock.unlock();
        }
        notifyListeners(newEndpoints);
    }

    /**
     * Sets the specified {@link Endpoint}s as current {@link Endpoint} list.
     */
    protected final void setEndpoints(Iterable<Endpoint> endpoints) {
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            this.endpoints = newEndpoints = ImmutableList.copyOf(endpoints);
        } finally {
            endpointsLock.unlock();
        }

        notifyListeners(newEndpoints);
        completeInitialEndpointsFuture(newEndpoints);
    }

    private void completeInitialEndpointsFuture(List<Endpoint> endpoints) {
        if (!endpoints.isEmpty() && !initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.complete(endpoints);
        }
    }

    @Override
    public void close() {
        if (!initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.completeExceptionally(new IllegalStateException(
                    getClass() + " has stopped before retrieving the initial endpoint list."));
        }
    }
}
