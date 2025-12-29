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

import com.linecorp.armeria.common.annotation.Nullable;

final class CombineLatest3Stream<A, B, C, O> extends RefCountedStream<O> {

    @FunctionalInterface
    interface TriFunction<A, B, C, O> {
        O apply(A a, B b, C c);
    }

    private final SnapshotStream<A> streamA;
    private final SnapshotStream<B> streamB;
    private final SnapshotStream<C> streamC;
    private final TriFunction<? super A, ? super B, ? super C, ? extends O> combiner;

    @Nullable
    private Subscription subA;
    @Nullable
    private Subscription subB;
    @Nullable
    private Subscription subC;

    private boolean aReady;
    private boolean bReady;
    private boolean cReady;

    @Nullable
    private A latestA;
    @Nullable
    private B latestB;
    @Nullable
    private C latestC;

    CombineLatest3Stream(SnapshotStream<A> streamA,
                         SnapshotStream<B> streamB,
                         SnapshotStream<C> streamC,
                         TriFunction<? super A, ? super B, ? super C, ? extends O> combiner) {
        this.streamA = streamA;
        this.streamB = streamB;
        this.streamC = streamC;
        this.combiner = combiner;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<O> watcher) {
        subA = streamA.subscribe(this::onA);
        subB = streamB.subscribe(this::onB);
        subC = streamC.subscribe(this::onC);

        return () -> {
            if (subA != null) {
                subA.close();
            }
            if (subB != null) {
                subB.close();
            }
            if (subC != null) {
                subC.close();
            }
            subA = null;
            subB = null;
            subC = null;
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

    private void onC(@Nullable C v, @Nullable Throwable t) {
        if (v == null) {
            emit(null, t);
            return;
        }
        latestC = v;
        cReady = true;
        maybeEmit();
    }

    private void maybeEmit() {
        if (!aReady || !bReady || !cReady) {
            return;
        }
        assert latestA != null;
        assert latestB != null;
        assert latestC != null;
        try {
            emit(combiner.apply(latestA, latestB, latestC), null);
        } catch (Throwable t) {
            emit(null, t);
        }
    }
}
