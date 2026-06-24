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

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.linecorp.armeria.xds.SnapshotWatcher;

final class CachingStream<K, T> {

    private final Function<? super K, ? extends SnapshotStream<T>> factory;
    private final Map<K, CacheEntry> cache = new HashMap<>();

    CachingStream(Function<? super K, ? extends SnapshotStream<T>> factory) {
        this.factory = requireNonNull(factory, "factory");
    }

    SnapshotStream<T> subscribe(K key) {
        requireNonNull(key, "key");
        return watcher -> {
            final CacheEntry entry = cache.computeIfAbsent(key, CacheEntry::new);
            return entry.subscribe(watcher);
        };
    }

    private final class CacheEntry extends RefCountedStream<T> {
        private final K key;

        CacheEntry(K key) {
            this.key = key;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<T> watcher) {
            return factory.apply(key).subscribe(watcher);
        }

        @Override
        protected void onStop() {
            cache.remove(key);
        }
    }
}
