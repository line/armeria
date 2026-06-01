/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.stream;

import java.util.LinkedHashSet;
import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.SnapshotWatcher;

/**
 * A {@link SnapshotStream} that manages subscriber reference counting.
 * The upstream subscription is started when the first watcher subscribes
 * and stopped when the last watcher unsubscribes.
 *
 * <p>Subclasses implement {@link #onStart} to set up the upstream data source
 * and optionally override {@link #onStop} for cleanup.
 *
 * @param <T> the type of snapshot values delivered by this stream
 */
@UnstableApi
public abstract class RefCountedStream<T> implements SnapshotStream<T> {

    private final Set<SnapshotWatcher<? super T>> watchers = new LinkedHashSet<>();

    @Nullable
    private T latestValue;

    @Nullable
    private Subscription upstreamSub;

    @Override
    public final Subscription subscribe(SnapshotWatcher<? super T> watcher) {
        if (latestValue != null) {
            watcher.onUpdate(latestValue, null);
        }

        if (!watchers.add(watcher)) {
            return Subscription.noop();
        }

        if (watchers.size() == 1) {
            try {
                upstreamSub = onStart(this::emit);
            } catch (Throwable t) {
                watchers.remove(watcher);
                watcher.onUpdate(null, t);
                return Subscription.noop();
            }
        }

        return () -> {
            watchers.remove(watcher);
            if (watchers.isEmpty()) {
                if (upstreamSub != null) {
                    upstreamSub.close();
                    upstreamSub = null;
                }
                onStop();
            }
        };
    }

    /**
     * Called when the first watcher subscribes. Implementations should set up the upstream
     * data source and deliver values to the given watcher.
     *
     * @param watcher the watcher to deliver upstream values to
     * @return a subscription to close when the upstream should be stopped
     */
    protected abstract Subscription onStart(SnapshotWatcher<T> watcher);

    /**
     * Called when the last watcher unsubscribes. Override to perform cleanup.
     */
    protected void onStop() {
    }

    /**
     * Emits a value or error to all current watchers.
     */
    public final void emit(@Nullable T value, @Nullable Throwable error) {
        if (value != null) {
            latestValue = value;
        }
        // Use a snapshot to allow re-entrant subscribe/unsubscribe during callbacks.
        final Object[] cpy = watchers.toArray();
        for (Object o : cpy) {
            @SuppressWarnings("unchecked")
            final SnapshotWatcher<? super T> w = (SnapshotWatcher<? super T>) o;
            w.onUpdate(value, error);
        }
    }

    /**
     * Returns whether this stream currently has any active watchers.
     */
    public boolean hasWatchers() {
        return !watchers.isEmpty();
    }
}
