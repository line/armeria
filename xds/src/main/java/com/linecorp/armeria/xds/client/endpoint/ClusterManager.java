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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.Listenable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.client.endpoint.ClusterManager.State;

import io.netty.util.concurrent.EventExecutor;

final class ClusterManager implements SnapshotWatcher<ListenerSnapshot>, AsyncCloseable,
                                      Listenable<State>, Consumer<List<Endpoint>> {

    private static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);
    private static final Map<ClusterSnapshot, ClusterEntry> INITIAL_CLUSTER_ENTRIES = new HashMap<>();

    private final EventExecutor eventLoop;
    private final SafeCloseable safeCloseable;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    private volatile Map<ClusterSnapshot, ClusterEntry> clusterEntries = INITIAL_CLUSTER_ENTRIES;
    @Nullable
    private volatile ListenerSnapshot listenerSnapshot;
    private final Set<CompletableFuture<?>> pendingRemovals = Sets.newConcurrentHashSet();
    private boolean closed;

    @GuardedBy("listenersLock")
    private final List<Consumer<? super State>> listeners = new ArrayList<>();
    private final ReentrantShortLock listenersLock = new ReentrantShortLock();

    ClusterManager(ListenerRoot listenerRoot) {
        eventLoop = listenerRoot.eventLoop();
        safeCloseable = listenerRoot;
        listenerRoot.addSnapshotWatcher(this);
    }

    ClusterManager(ClusterSnapshot clusterSnapshot) {
        checkArgument(clusterSnapshot.endpointSnapshot() != null,
                      "An endpoint snapshot must exist for the provided (%s)", clusterSnapshot);
        eventLoop = CommonPools.workerGroup().next();
        clusterEntries = ImmutableMap.of(clusterSnapshot, new ClusterEntry(clusterSnapshot, this));
        safeCloseable = () -> {};
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        final Map<ClusterSnapshot, ClusterEntry> clusterEntries = this.clusterEntries;
        for (Entry<ClusterSnapshot, ClusterEntry> entry: clusterEntries.entrySet()) {
            // Just use the first snapshot for now
            final ClusterEntry clusterEntry = entry.getValue();
            return clusterEntry.selectNow(ctx);
        }
        return null;
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot listenerSnapshot) {
        if (closed) {
            return;
        }
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return;
        }

        // it is important that the entries are added in order of ClusterSnapshot#index
        // so that the first matching route is selected in #selectNow
        final ImmutableMap.Builder<ClusterSnapshot, ClusterEntry> mappingBuilder =
                ImmutableMap.builder();
        final Map<ClusterSnapshot, ClusterEntry> oldEndpointGroups = clusterEntries;
        for (ClusterSnapshot clusterSnapshot: routeSnapshot.clusterSnapshots()) {
            if (clusterSnapshot.endpointSnapshot() == null) {
                continue;
            }
            ClusterEntry clusterEntry = oldEndpointGroups.get(clusterSnapshot);
            if (clusterEntry == null) {
                clusterEntry = new ClusterEntry(clusterSnapshot, this);
            }
            mappingBuilder.put(clusterSnapshot, clusterEntry);
        }
        clusterEntries = mappingBuilder.build();
        this.listenerSnapshot = listenerSnapshot;
        notifyListeners();
        cleanupEndpointGroups(clusterEntries, oldEndpointGroups);
    }

    private void cleanupEndpointGroups(Map<ClusterSnapshot, ClusterEntry> newEndpointGroups,
                                       Map<ClusterSnapshot, ClusterEntry> oldEndpointGroups) {
        for (Entry<ClusterSnapshot, ClusterEntry> entry: oldEndpointGroups.entrySet()) {
            if (newEndpointGroups.containsKey(entry.getKey())) {
                continue;
            }
            final ClusterEntry clusterEntry = entry.getValue();
            final CompletableFuture<?> closeFuture = clusterEntry.closeAsync();
            pendingRemovals.add(closeFuture);
            closeFuture.handle((ignored, t) -> {
                pendingRemovals.remove(closeFuture);
                return null;
            });
        }
    }

    @Override
    public void addListener(Consumer<? super State> listener) {
        listenersLock.lock();
        try {
            listeners.add(listener);
        } finally {
            listenersLock.unlock();
        }
        if (clusterEntries != INITIAL_CLUSTER_ENTRIES) {
            listener.accept(state());
        }
    }

    @Override
    public void removeListener(Consumer<?> listener) {
        listenersLock.lock();
        try {
            listeners.remove(listener);
        } finally {
            listenersLock.unlock();
        }
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        notifyListeners();
    }

    private State state() {
        final Map<ClusterSnapshot, ClusterEntry> clusterEntries = this.clusterEntries;
        if (clusterEntries.isEmpty()) {
            return new State(listenerSnapshot, ImmutableList.of());
        }
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        for (ClusterEntry clusterEntry: clusterEntries.values()) {
            endpointsBuilder.addAll(clusterEntry.allEndpoints());
        }
        return new State(listenerSnapshot, endpointsBuilder.build());
    }

    private void notifyListeners() {
        if (clusterEntries == INITIAL_CLUSTER_ENTRIES) {
            return;
        }
        final State state = state();
        listenersLock.lock();
        try {
            for (Consumer<? super State> listener : listeners) {
                try {
                    listener.accept(state);
                } catch (Exception e) {
                    logger.warn("Unexpected exception while notifying listeners");
                }
            }
        } finally {
            listenersLock.unlock();
        }
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::closeAsync);
            return closeFuture;
        }
        if (closed) {
            return closeFuture;
        }
        closed = true;
        safeCloseable.close();
        final List<CompletableFuture<?>> closeFutures = Streams.concat(
                clusterEntries.values().stream().map(ClusterEntry::closeAsync),
                pendingRemovals.stream()).collect(Collectors.toList());
        CompletableFutures.allAsList(closeFutures).handle((ignored, e) -> closeFuture.complete(null));
        return closeFuture;
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    static final class State {

        static final State INITIAL_STATE = new State(null, ImmutableList.of());

        // There is no guarantee that endpoints corresponds to the listenerSnapshot.
        // However, the state will be eventually consistent and the state is only used
        // for signalling purposes so there should be no issue.
        @Nullable
        private final ListenerSnapshot listenerSnapshot;
        private final List<Endpoint> endpoints;

        private State(@Nullable ListenerSnapshot listenerSnapshot, List<Endpoint> endpoints) {
            this.listenerSnapshot = listenerSnapshot;
            this.endpoints = ImmutableList.copyOf(endpoints);
        }

        List<Endpoint> endpoints() {
            return endpoints;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            final State state = (State) object;
            return Objects.equal(listenerSnapshot, state.listenerSnapshot) &&
                   Objects.equal(endpoints, state.endpoints);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(listenerSnapshot, endpoints);
        }
    }
}
