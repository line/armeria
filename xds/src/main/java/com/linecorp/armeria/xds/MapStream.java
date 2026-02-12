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

final class MapStream<I, O> extends RefCountedStream<O> {

    private final SnapshotStream<I> upstream;
    private final Function<? super I, ? extends O> mapper;

    MapStream(SnapshotStream<I> upstream, Function<? super I, ? extends O> mapper) {
        this.upstream = upstream;
        this.mapper = mapper;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<O> watcher) {
        return upstream.subscribe((snapshot, t) -> {
            if (snapshot == null) {
                emit(null, t);
                return;
            }
            try {
                emit(mapper.apply(snapshot), t);
            } catch (Throwable t2) {
                emit(null, t2);
            }
        });
    }
}
