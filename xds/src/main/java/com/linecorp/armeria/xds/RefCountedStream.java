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

package com.linecorp.armeria.xds;

import java.util.HashSet;
import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;

abstract class RefCountedStream<T> implements SnapshotStream<T> {

    private final Set<SnapshotWatcher<? super T>> watchers = new HashSet<>();

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

    protected abstract Subscription onStart(SnapshotWatcher<T> watcher);

    protected void onStop() {
    }

    protected void emit(@Nullable T value, @Nullable Throwable error) {
        if (value != null) {
            latestValue = value;
        }
        final Object[] snapshot = watchers.toArray();
        for (Object o : snapshot) {
            @SuppressWarnings("unchecked")
            final SnapshotWatcher<? super T> w = (SnapshotWatcher<? super T>) o;
            w.onUpdate(value, error);
        }
    }

    boolean hasWatchers() {
        return !watchers.isEmpty();
    }
}
