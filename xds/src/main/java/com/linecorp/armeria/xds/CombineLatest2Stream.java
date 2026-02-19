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

import java.util.function.BiFunction;

import com.linecorp.armeria.common.annotation.Nullable;

final class CombineLatest2Stream<A, B, O> extends RefCountedStream<O> {

    private final SnapshotStream<A> streamA;
    private final SnapshotStream<B> streamB;
    private final BiFunction<? super A, ? super B, ? extends O> combiner;

    @Nullable
    private Subscription subA;
    @Nullable
    private Subscription subB;

    private boolean aReady;
    private boolean bReady;
    @Nullable
    private A latestA;
    @Nullable
    private B latestB;

    CombineLatest2Stream(SnapshotStream<A> streamA,
                         SnapshotStream<B> streamB,
                         BiFunction<? super A, ? super B, ? extends O> combiner) {
        this.streamA = streamA;
        this.streamB = streamB;
        this.combiner = combiner;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<O> watcher) {
        subA = streamA.subscribe(this::onA);
        subB = streamB.subscribe(this::onB);

        return () -> {
            if (subA != null) {
                subA.close();
            }
            if (subB != null) {
                subB.close();
            }
            subA = null;
            subB = null;
        };
    }

    private void onA(@Nullable A v, @Nullable Throwable t) {
        if (v == null) {
            emit(null, t);
            return;
        }

        latestA = v;
        aReady = true;
        maybeEmit();
    }

    private void onB(@Nullable B v, @Nullable Throwable t) {
        if (v == null) {
            emit(null, t);
            return;
        }

        latestB = v;
        bReady = true;
        maybeEmit();
    }

    private void maybeEmit() {
        if (!aReady || !bReady) {
            return;
        }
        try {
            emit(combiner.apply(latestA, latestB), null);
        } catch (Throwable t) {
            emit(null, t);
        }
    }
}
