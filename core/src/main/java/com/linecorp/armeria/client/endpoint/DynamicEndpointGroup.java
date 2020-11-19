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
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;

/**
 * A dynamic {@link EndpointGroup}. The list of {@link Endpoint}s can be updated dynamically.
 */
public class DynamicEndpointGroup
        extends AbstractListenable<List<Endpoint>>
        implements EndpointGroup, ListenableAsyncCloseable {

    // An empty list of endpoints we also use as a marker that we have not initialized endpoints yet.
    private static final List<Endpoint> UNINITIALIZED_ENDPOINTS = Collections.unmodifiableList(
            new ArrayList<>());

    private final EndpointSelectionStrategy selectionStrategy;
    private final AtomicReference<EndpointSelector> selector = new AtomicReference<>();
    private volatile List<Endpoint> endpoints = UNINITIALIZED_ENDPOINTS;
    private final Lock endpointsLock = new ReentrantLock();

    private final CompletableFuture<Void> initialEndpointsSet = new CompletableFuture<>();
    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new EventLoopCheckingFuture<>();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    /**
     * Creates a new empty {@link DynamicEndpointGroup} that uses
     * {@link EndpointSelectionStrategy#weightedRoundRobin()} as its {@link EndpointSelectionStrategy}.
     */
    public DynamicEndpointGroup() {
        this(EndpointSelectionStrategy.weightedRoundRobin());
    }

    /**
     * Creates a new empty {@link DynamicEndpointGroup} that uses the specified
     * {@link EndpointSelectionStrategy}.
     */
    public DynamicEndpointGroup(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        initialEndpointsSet.thenApply(unused -> {
            initialEndpointsFuture.complete(new LazyList<>(this::endpoints));
            return null;
        });
    }

    @Override
    public final List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public final EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    @Override
    public final Endpoint selectNow(ClientRequestContext ctx) {
        return maybeCreateSelector().selectNow(ctx);
    }

    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor,
                                                    long timeoutMillis) {
        return maybeCreateSelector().select(ctx, executor, timeoutMillis);
    }

    /**
     * Creates an {@link EndpointSelector} lazily, so that the {@link EndpointSelector} is created after
     * the subclasses' constructor finishes its job.
     */
    private EndpointSelector maybeCreateSelector() {
        final EndpointSelector selector = this.selector.get();
        if (selector != null) {
            return selector;
        }

        final EndpointSelector newSelector = selectionStrategy.newSelector(this);
        if (this.selector.compareAndSet(null, newSelector)) {
            return newSelector;
        }

        return this.selector.get();
    }

    /**
     * Returns the {@link CompletableFuture} which is completed when the initial {@link Endpoint}s are ready.
     */
    @Override
    public final CompletableFuture<List<Endpoint>> whenReady() {
        return initialEndpointsFuture;
    }

    /**
     * Adds the specified {@link Endpoint} to current {@link Endpoint} list.
     */
    protected final void addEndpoint(Endpoint e) {
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            final List<Endpoint> newEndpointsUnsorted = Lists.newArrayList(endpoints);
            newEndpointsUnsorted.add(e);
            endpoints = newEndpoints = ImmutableList.sortedCopyOf(newEndpointsUnsorted);
        } finally {
            endpointsLock.unlock();
        }

        notifyListeners(newEndpoints);
        completeInitialEndpointsSet(newEndpoints);
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
        final List<Endpoint> oldEndpoints = this.endpoints;
        final List<Endpoint> newEndpoints = ImmutableList.sortedCopyOf(endpoints);

        if (!hasChanges(oldEndpoints, newEndpoints)) {
            return;
        }

        endpointsLock.lock();
        try {
            this.endpoints = newEndpoints;
        } finally {
            endpointsLock.unlock();
        }

        notifyListeners(newEndpoints);
        completeInitialEndpointsSet(newEndpoints);
    }

    private static boolean hasChanges(List<Endpoint> oldEndpoints, List<Endpoint> newEndpoints) {
        if (oldEndpoints == UNINITIALIZED_ENDPOINTS) {
            return true;
        }

        if (oldEndpoints.size() != newEndpoints.size()) {
            return true;
        }

        for (int i = 0; i < oldEndpoints.size(); i++) {
            final Endpoint a = oldEndpoints.get(i);
            final Endpoint b = newEndpoints.get(i);
            if (!a.equals(b) || a.weight() != b.weight()) {
                return true;
            }
        }

        return false;
    }

    private void completeInitialEndpointsSet(List<Endpoint> endpoints) {
        if (endpoints != UNINITIALIZED_ENDPOINTS && !initialEndpointsSet.isDone()) {
            initialEndpointsSet.complete(null);
        }
    }

    @Override
    public final boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public final boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public final CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    @Override
    public final CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        if (!initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.cancel(false);
        }
        doCloseAsync(future);
    }

    /**
     * Override this method to release the resources held by this {@link EndpointGroup} and complete
     * the specified {@link CompletableFuture}.
     */
    protected void doCloseAsync(CompletableFuture<?> future) {
        future.complete(null);
    }

    @Override
    public final void close() {
        closeable.close();
    }
}
