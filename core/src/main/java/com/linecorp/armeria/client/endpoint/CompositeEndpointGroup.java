/*
 * Copyright 2019 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;

/**
 * An {@link EndpointGroup} that merges the result of any number of other {@link EndpointGroup}s.
 */
final class CompositeEndpointGroup extends AbstractEndpointGroup implements ListenableAsyncCloseable {

    private final List<EndpointGroup> endpointGroups;

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture;

    private final EndpointSelectionStrategy selectionStrategy;
    private final EndpointSelector selector;
    private final long selectionTimeoutMillis;

    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    private final AtomicReference<List<Endpoint>> merged = new AtomicReference<>();

    /**
     * Constructs a new {@link CompositeEndpointGroup} that merges all the given {@code endpointGroups}.
     */
    CompositeEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                           Iterable<EndpointGroup> endpointGroups) {
        this.endpointGroups = ImmutableList.copyOf(requireNonNull(endpointGroups, "endpointGroups"));
        merged.set(ImmutableList.of());

        long selectionTimeoutMillis = 0;
        for (EndpointGroup endpointGroup : endpointGroups) {
            endpointGroup.addListener(unused -> notifyListeners(rebuildEndpoints()));
            selectionTimeoutMillis = Math.max(selectionTimeoutMillis,
                                              endpointGroup.selectionTimeoutMillis());
        }
        this.selectionTimeoutMillis = selectionTimeoutMillis;

        initialEndpointsFuture =
                CompletableFuture.anyOf(this.endpointGroups.stream()
                                                           .map(EndpointGroup::whenReady)
                                                           .toArray(CompletableFuture[]::new))
                                 .thenApply(unused -> rebuildEndpoints());

        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        selector = requireNonNull(selectionStrategy, "selectionStrategy").newSelector(this);
    }

    @Override
    public List<Endpoint> endpoints() {
        final List<Endpoint> endpoints = merged.get();
        assert endpoints != null;
        return endpoints;
    }

    private List<Endpoint> rebuildEndpoints() {
        for (;;) {
            // Get the current endpoints before making a new endpoints list.
            final List<Endpoint> oldEndpoints = merged.get();
            final List<Endpoint> newEndpoints = new ArrayList<>();
            for (EndpointGroup endpointGroup : endpointGroups) {
                newEndpoints.addAll(endpointGroup.endpoints());
            }
            // Do not use ImmutableList because the empty ImmutableList uses a singleton instance.
            final List<Endpoint> endpoints = Collections.unmodifiableList(newEndpoints);
            if (merged.compareAndSet(oldEndpoints, endpoints)) {
                return endpoints;
            }
            // Changed by another thread while we were building a new one. Try again.
        }
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    @Nullable
    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return selector.selectNow(ctx);
    }

    @Deprecated
    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return select(ctx, executor);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor) {
        return selector.select(ctx, executor);
    }

    @Override
    public long selectionTimeoutMillis() {
        return selectionTimeoutMillis;
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return initialEndpointsFuture;
    }

    @Override
    public boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        final CompletableFuture<?>[] closeFutures =
                endpointGroups.stream()
                              .map(AsyncCloseable::closeAsync)
                              .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(closeFutures).handle((unused, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(null);
            }
            return null;
        });
    }

    @Override
    public void close() {
        closeable.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointGroups", endpointGroups)
                          .toString();
    }
}
