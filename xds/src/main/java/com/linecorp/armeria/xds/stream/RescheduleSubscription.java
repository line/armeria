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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.SnapshotWatcher;

import io.netty.util.concurrent.EventExecutor;

final class RescheduleSubscription<T> implements SnapshotWatcher<T>, Subscription {

    private final SnapshotWatcher<? super T> downstream;
    private final EventExecutor eventLoop;
    @Nullable
    private Subscription upstream;
    private boolean closed;

    RescheduleSubscription(SnapshotWatcher<? super T> downstream, EventExecutor eventLoop) {
        this.downstream = downstream;
        this.eventLoop = eventLoop;
    }

    void setUpstream(Subscription upstream) {
        this.upstream = upstream;
    }

    @Override
    public void onUpdate(@Nullable T value, @Nullable Throwable error) {
        eventLoop.execute(() -> {
            if (!closed) {
                downstream.onUpdate(value, error);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        if (upstream != null) {
            upstream.close();
        }
    }
}
