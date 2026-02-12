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

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

final class CombineNLatestStream<T> extends RefCountedStream<List<T>> {

    private final Object[] latest;
    private final boolean[] ready;
    private final List<? extends SnapshotStream<T>> streams;
    private int readyCount;

    CombineNLatestStream(List<? extends SnapshotStream<T>> streams) {
        this.streams = streams;
        latest = new Object[streams.size()];
        ready = new boolean[streams.size()];
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<List<T>> watcher) {
        if (streams.isEmpty()) {
            return SnapshotStream.just(ImmutableList.<T>of()).subscribe(watcher);
        }
        final ImmutableList.Builder<Subscription> builder = ImmutableList.builder();
        for (int i = 0; i < streams.size(); i++) {
            final int id = i;
            final Subscription subscription =
                    streams.get(i).subscribe((snapshot, t) -> onEvent(id, snapshot, t));
            builder.add(subscription);
        }
        final List<Subscription> subscriptions = builder.build();
        return () -> subscriptions.forEach(Subscription::close);
    }

    private void onEvent(int idx, @Nullable T snapshot, @Nullable Throwable t) {
        if (snapshot == null) {
            emit(null, t);
            return;
        }

        latest[idx] = snapshot;
        if (!ready[idx]) {
            ready[idx] = true;
            readyCount++;
        }
        if (readyCount < streams.size()) {
            return;
        }

        final ImmutableList.Builder<T> out = ImmutableList.builderWithExpectedSize(streams.size());
        for (int i = 0; i < streams.size(); i++) {
            @SuppressWarnings("unchecked")
            final T v = (T) latest[i];
            out.add(v);
        }
        emit(out.build(), null);
    }
}
