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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;

/**
 * An {@link EndpointGroup} that merges the result of any number of other {@link EndpointGroup}s.
 */
final class CompositeEndpointGroup
        extends AbstractListenable<List<Endpoint>>
        implements EndpointGroup, ListenableAsyncCloseable {

    private final List<EndpointGroup> endpointGroups;

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new EventLoopCheckingFuture<>();
    private final AtomicBoolean dirty;

    private final EndpointSelectionStrategy selectionStrategy;
    private final EndpointSelector selector;

    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    private volatile List<Endpoint> merged = ImmutableList.of();

    /**
     * Constructs a new {@link CompositeEndpointGroup} that merges all the given {@code endpointGroups}.
     */
    CompositeEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                           Iterable<EndpointGroup> endpointGroups) {

        this.endpointGroups = ImmutableList.copyOf(requireNonNull(endpointGroups, "endpointGroups"));
        dirty = new AtomicBoolean(true);

        for (EndpointGroup endpointGroup : endpointGroups) {
            endpointGroup.addListener(unused -> {
                dirty.set(true);
                notifyListeners(endpoints());
            });
        }

        CompletableFuture.anyOf(this.endpointGroups.stream()
                                                   .map(EndpointGroup::whenReady)
                                                   .toArray(CompletableFuture[]::new))
                         .handle((unused, cause) -> {
                             if (cause != null) {
                                 initialEndpointsFuture.completeExceptionally(cause);
                             } else {
                                 initialEndpointsFuture.complete(new LazyList<>(this::endpoints));
                             }
                             return null;
                         });;

        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        selector = requireNonNull(selectionStrategy, "selectionStrategy").newSelector(this);
    }

    @Override
    public List<Endpoint> endpoints() {
        if (!dirty.get()) {
            return merged;
        }

        if (!dirty.compareAndSet(true, false)) {
            // Another thread might be updating merged at this time, but endpoint groups are allowed to take a
            // little bit of time to reflect updates.
            return merged;
        }

        final ImmutableList.Builder<Endpoint> newEndpoints = ImmutableList.builder();
        for (EndpointGroup endpointGroup : endpointGroups) {
            newEndpoints.addAll(endpointGroup.endpoints());
        }

        return merged = newEndpoints.build();
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
        return selector.select(ctx, executor, timeoutMillis);
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
