/*
 * Copyright 2025 LY Corporation
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

import java.util.ArrayList;
import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;

class DelegatingWatcher<T> implements SnapshotWatcher<T> {

    private final List<SnapshotWatcher<? super T>> watchers = new ArrayList<>();
    @Nullable
    private T snapshot;

    void addWatcher(SnapshotWatcher<? super T> watcher) {
        watchers.add(watcher);
        if (snapshot != null) {
            watcher.onUpdate(snapshot, null);
        }
    }

    void removeWatcher(SnapshotWatcher<? super T> watcher) {
        watchers.remove(watcher);
    }

    @Override
    public void onUpdate(@Nullable T snapshot, @Nullable Throwable t) {
        if (snapshot == null) {
            for (SnapshotWatcher<? super T> watcher : watchers) {
                watcher.onUpdate(null, t);
            }
            return;
        }
        this.snapshot = snapshot;
        for (SnapshotWatcher<? super T> watcher : watchers) {
            try {
                watcher.onUpdate(snapshot, null);
            } catch (Exception e) {
                watcher.onUpdate(null, e);
            }
        }
    }

    public boolean hasWatchers() {
        return !watchers.isEmpty();
    }
}
