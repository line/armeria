/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A dynamic {@link EndpointGroup}. The list of {@link Endpoint}s can be updated dynamically.
 */
public class DynamicEndpointGroup implements EndpointGroup {
    private final Set<Consumer<Collection<Endpoint>>> updateListeners = new CopyOnWriteArraySet<>();
    private volatile List<Endpoint> endpoints = ImmutableList.of();
    private final Lock endpointsLock = new ReentrantLock();

    @Override
    public final List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * Adds the specified {@link Endpoint} to current {@link Endpoint} list.
     */
    protected final void addEndpoint(Endpoint e) {
        try {
            endpointsLock.lock();
            ImmutableList.Builder<Endpoint> newEndpointsBuilder = ImmutableList.builder();
            newEndpointsBuilder.addAll(endpoints);
            newEndpointsBuilder.add(e);
            endpoints = newEndpointsBuilder.build();
            notifyListeners();
        } finally {
            endpointsLock.unlock();
        }
    }

    /**
     * Removes the specified {@link Endpoint} from current {@link Endpoint} list.
     */
    protected final void removeEndpoint(Endpoint e) {
        try {
            endpointsLock.lock();
            endpoints = endpoints.stream()
                                 .filter(endpoint -> !endpoint.equals(e))
                                 .collect(toImmutableList());
            notifyListeners();
        } finally {
            endpointsLock.unlock();
        }
    }

    /**
     * Sets the specified {@link Endpoint}s as current {@link Endpoint} list.
     */
    protected final void setEndpoints(Iterable<Endpoint> endpoints) {
        try {
            endpointsLock.lock();
            this.endpoints = ImmutableList.copyOf(endpoints);
            notifyListeners();
        } finally {
            endpointsLock.unlock();
        }
    }

    /**
     * Notify the {@link Endpoint}s changes to listeners.
     */
    private void notifyListeners() {
        List<Endpoint> endpoints = Collections.unmodifiableList(this.endpoints);
        Set<Consumer<Collection<Endpoint>>> updateListeners = this.updateListeners;
        for (Consumer<Collection<Endpoint>> listener : updateListeners) {
            listener.accept(endpoints);
        }
    }

    /**
     * Adds a {@link Consumer} that will be invoked when a {@link EndpointGroup} changes {@link Endpoint} list.
     */
    public final void addListener(Consumer<Collection<Endpoint>> listener) {
        requireNonNull(listener, "listener");
        updateListeners.add(listener);
    }

    /**
     * Remove a listener.
     */
    public final void removeListener(Consumer<?> listener) {
        requireNonNull(listener, "listener");
        updateListeners.remove(listener);
    }
}
