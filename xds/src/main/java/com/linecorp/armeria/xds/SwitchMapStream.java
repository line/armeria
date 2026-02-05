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

import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;

final class SwitchMapStream<I, O> extends RefCountedStream<O> {

    private final SnapshotStream<I> upstream;
    private final Function<? super I, ? extends SnapshotStream<? extends O>> mapper;

    @Nullable
    private Subscription upstreamSub;
    @Nullable
    private Subscription innerSub;
    private long epoch;

    SwitchMapStream(SnapshotStream<I> upstream,
                    Function<? super I, ? extends SnapshotStream<? extends O>> mapper) {
        this.upstream = upstream;
        this.mapper = mapper;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<O> watcher) {
        final long subscriptionEpoch = epoch;
        upstreamSub = upstream.subscribe((snapshot, err) -> {

            if (err != null) {
                emit(null, err);
                return;
            }

            if (innerSub != null) {
                innerSub.close();
                innerSub = null;
            }

            final SnapshotStream<? extends O> innerStream;
            try {
                innerStream = mapper.apply(snapshot);
            } catch (Throwable t) {
                emit(null, t);
                return;
            }

            innerSub = innerStream.subscribe(this::emit);
            // If stopped during subscription, close immediately
            if (subscriptionEpoch != epoch) {
                innerSub.close();
                innerSub = null;
            }
        });

        return () -> {
            epoch++;
            if (innerSub != null) {
                innerSub.close();
            }
            innerSub = null;
            if (upstreamSub != null) {
                upstreamSub.close();
            }
            upstreamSub = null;
        };
    }
}
