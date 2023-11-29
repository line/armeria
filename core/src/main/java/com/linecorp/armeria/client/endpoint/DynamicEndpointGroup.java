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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * A dynamic {@link EndpointGroup}. The list of {@link Endpoint}s can be updated dynamically.
 */
public class DynamicEndpointGroup extends AbstractEndpointGroup implements ListenableAsyncCloseable {

    /**
     * Returns a newly created builder.
     */
    @UnstableApi
    public static DynamicEndpointGroupBuilder builder() {
        return new DynamicEndpointGroupBuilder();
    }

    // An empty list of endpoints we also use as a marker that we have not initialized endpoints yet.
    private static final List<Endpoint> UNINITIALIZED_ENDPOINTS = Collections.unmodifiableList(
            new ArrayList<>());

    private final EndpointSelectionStrategy selectionStrategy;
    private final AtomicReference<EndpointSelector> selector = new AtomicReference<>();
    private volatile List<Endpoint> endpoints = UNINITIALIZED_ENDPOINTS;
    private final Lock endpointsLock = new ReentrantShortLock();

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new InitialEndpointsFuture();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);
    private final boolean allowEmptyEndpoints;
    private final long selectionTimeoutMillis;

    /**
     * Creates a new empty instance, using {@link EndpointSelectionStrategy#weightedRoundRobin()}
     * and allowing an empty {@link Endpoint} list.
     */
    public DynamicEndpointGroup() {
        this(EndpointSelectionStrategy.weightedRoundRobin());
    }

    /**
     * Creates a new empty instance, allowing an empty {@link Endpoint} list.
     *
     * @param selectionStrategy the {@link EndpointSelectionStrategy} of this {@link EndpointGroup}
     */
    public DynamicEndpointGroup(EndpointSelectionStrategy selectionStrategy) {
        this(selectionStrategy, true);
    }

    /**
     * Creates a new empty instance, using {@link EndpointSelectionStrategy#weightedRoundRobin()}.
     *
     * @param allowEmptyEndpoints whether to allow an empty {@link Endpoint} list
     */
    protected DynamicEndpointGroup(boolean allowEmptyEndpoints) {
        this(EndpointSelectionStrategy.weightedRoundRobin(), allowEmptyEndpoints);
    }

    /**
     * Creates a new empty instance, using {@link EndpointSelectionStrategy#weightedRoundRobin()}.
     *
     * @param allowEmptyEndpoints whether to allow an empty {@link Endpoint} list
     * @param selectionTimeoutMillis the timeout to wait until a successful {@link Endpoint} selection.
     *                               {@code 0} disables the timeout. If unspecified,
     *                               {@link Flags#defaultConnectTimeoutMillis()} is used by default.
     */
    protected DynamicEndpointGroup(boolean allowEmptyEndpoints, long selectionTimeoutMillis) {
        this(EndpointSelectionStrategy.weightedRoundRobin(), allowEmptyEndpoints, selectionTimeoutMillis);
    }

    /**
     * Creates a new empty instance.
     *
     * @param selectionStrategy the {@link EndpointSelectionStrategy} of this {@link EndpointGroup}
     * @param allowEmptyEndpoints whether to allow an empty {@link Endpoint} list
     */
    protected DynamicEndpointGroup(EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints) {
        this(selectionStrategy, allowEmptyEndpoints, Flags.defaultConnectTimeoutMillis());
    }

    /**
     * Creates a new empty instance.
     *
     * @param selectionStrategy the {@link EndpointSelectionStrategy} of this {@link EndpointGroup}
     * @param allowEmptyEndpoints whether to allow an empty {@link Endpoint} list
     * @param selectionTimeoutMillis the timeout to wait until a successful {@link Endpoint} selection.
     *                               {@code 0} disables the timeout. If unspecified,
     *                               {@link Flags#defaultConnectTimeoutMillis()} is used by default.
     */
    protected DynamicEndpointGroup(EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints,
                                   long selectionTimeoutMillis) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        this.allowEmptyEndpoints = allowEmptyEndpoints;
        checkArgument(selectionTimeoutMillis >= 0, "selectionTimeoutMillis: %s (expected: >= 0)",
                      selectionTimeoutMillis);
        if (selectionTimeoutMillis == 0) {
            selectionTimeoutMillis = Long.MAX_VALUE;
        }
        this.selectionTimeoutMillis = selectionTimeoutMillis;
    }

    /**
     * Returns whether this {@link EndpointGroup} allows an empty {@link Endpoint} list.
     */
    @UnstableApi
    public boolean allowsEmptyEndpoints() {
        return allowEmptyEndpoints;
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
    public long selectionTimeoutMillis() {
        return selectionTimeoutMillis;
    }

    @Deprecated
    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor,
                                                    long timeoutMillis) {
        return select(ctx, executor);
    }

    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor) {
        return maybeCreateSelector().select(ctx, executor);
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

        maybeCompleteInitialEndpointsFuture(newEndpoints);
        notifyListeners(newEndpoints);
    }

    /**
     * Removes the specified {@link Endpoint} from current {@link Endpoint} list.
     */
    protected final void removeEndpoint(Endpoint e) {
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            if (!allowEmptyEndpoints && endpoints.size() == 1) {
                return;
            }
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
        if (!allowEmptyEndpoints && Iterables.isEmpty(endpoints)) {
            return;
        }
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            final List<Endpoint> oldEndpoints = this.endpoints;
            newEndpoints = ImmutableList.sortedCopyOf(endpoints);
            if (!hasChanges(oldEndpoints, newEndpoints)) {
                return;
            }
            this.endpoints = newEndpoints;
        } finally {
            endpointsLock.unlock();
        }

        maybeCompleteInitialEndpointsFuture(newEndpoints);
        notifyListeners(newEndpoints);
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
            if (!a.equals(b) || a.weight() != b.weight() || !a.attrs().equals(b.attrs())) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected List<Endpoint> latestValue() {
        final List<Endpoint> endpoints = this.endpoints;
        if (endpoints == UNINITIALIZED_ENDPOINTS) {
            return null;
        } else {
            return endpoints;
        }
    }

    private void maybeCompleteInitialEndpointsFuture(List<Endpoint> endpoints) {
        if (endpoints != UNINITIALIZED_ENDPOINTS && !initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.complete(endpoints);
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

    @Override
    public String toString() {
        return toString(unused -> {});
    }

    /**
     * Returns the string representation of this {@link DynamicEndpointGroup}. Specify a {@link Consumer}
     * to add more fields to the returned string, e.g.
     * <pre>{@code
     * > @Override
     * > public String toString() {
     * >     return toString(buf -> {
     * >         buf.append(", foo=").append(foo);
     * >         buf.append(", bar=").append(bar);
     * >     });
     * > }
     * }</pre>
     *
     * @param builderMutator the {@link Consumer} that appends the additional fields into the given
     *                       {@link StringBuilder}.
     */
    @UnstableApi
    protected final String toString(Consumer<? super StringBuilder> builderMutator) {
        final StringBuilder buf = new StringBuilder();
        buf.append(getClass().getSimpleName());
        buf.append("{selectionStrategy=").append(selectionStrategy.getClass());
        buf.append(", allowsEmptyEndpoints=").append(allowEmptyEndpoints);
        buf.append(", initialized=").append(initialEndpointsFuture.isDone());
        buf.append(", numEndpoints=").append(endpoints.size());
        buf.append(", endpoints=").append(truncate(endpoints, 10));
        builderMutator.accept(buf);
        return buf.append('}').toString();
    }

    private class InitialEndpointsFuture extends EventLoopCheckingFuture<List<Endpoint>> {

        @Override
        public List<Endpoint> get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return super.get(timeout, unit);
            } catch (TimeoutException e) {
                final TimeoutException timeoutException = new TimeoutException(
                        InitialEndpointsFuture.class.getSimpleName() + " is timed out after " +
                        unit.toMillis(timeout) + " milliseconds. endpoint group: " + DynamicEndpointGroup.this);
                timeoutException.initCause(e);
                throw timeoutException;
            }
        }
    }
}
