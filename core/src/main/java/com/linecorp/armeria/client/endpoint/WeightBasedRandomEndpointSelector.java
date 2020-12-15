/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.client.endpoint;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.Endpoint;

final class WeightBasedRandomEndpointSelector {
    private static final AtomicIntegerFieldUpdater<WeightBasedRandomEndpointSelector> removingEntryUpdater =
            AtomicIntegerFieldUpdater.newUpdater(WeightBasedRandomEndpointSelector.class, "removingEntry");

    private final List<Entry> entries;
    private final long totalWeight;
    private final List<Entry> currentEntries;
    private long currentTotalWeight;

    private volatile int removingEntry;

    WeightBasedRandomEndpointSelector(List<Endpoint> endpoints) {
        final ImmutableList.Builder<Entry> builder = ImmutableList.builder();

        long totalWeight = 0;
        for (Endpoint endpoint : endpoints) {
            builder.add(new Entry(endpoint, totalWeight));
            totalWeight += endpoint.weight();
        }
        this.totalWeight = totalWeight;
        currentTotalWeight = totalWeight;
        entries = builder.build();
        currentEntries = new CopyOnWriteArrayList<>(entries);
    }

    @VisibleForTesting
    List<Entry> entries() {
        return entries;
    }

    @Nullable
    Endpoint selectEndpoint() {
        if (entries.isEmpty()) {
            return null;
        }
        for (;;) {
            // selectEndpoint0() returns null if the endpoint is selected more than its weight while
            // other endpoints are not. In that case the entry of that endpoint is removed and returns null.
            // So we should loop to select another endpoint.
            // This guarantees that the endpoint whose weight is very low is always selected when
            // selectEndpoint() is called by totalWeight times even though we use random.
            final Endpoint endpoint = selectEndpoint0();
            if (endpoint != null) {
                return endpoint;
            }
        }
    }

    @Nullable
    Endpoint selectEndpoint0() {
        final long nextLong = ThreadLocalRandom.current().nextLong(currentTotalWeight);
        // There's a chance that currentTotalWeight is changed before looping currentEntries.
        // However, we have counters and choosing an endpoint doesn't have to be exact so no big deal.
        // TODO(minwoox): Use binary search when the number of endpoints is greater than N.
        Endpoint selected = null;
        for (Entry entry : currentEntries) {
            if (entry.lowerBound >= nextLong) {
                if (entry.increaseCounter()) {
                    selected = entry.endpoint();
                }
                if (!entry.isFull()) {
                    return selected;
                }

                // The entry is full so we should remove the entry from currentEntries.
                synchronized (currentEntries) {
                    // Check again not to remove the entry where reset() is called by another thread.
                    if (!entry.isFull()) {
                        return selected;
                    }
                    if (currentEntries.remove(entry)) {
                        if (currentEntries.isEmpty()) {
                            reset();
                        } else {
                            currentTotalWeight -= entry.endpoint().weight();
                        }
                    } else {
                        // The entry is removed by another thread.
                    }
                }
                return selected;
            }
        }

        return null;
    }

    private void reset() {
        for (Entry entry : entries) {
            entry.set(0);
        }
        currentEntries.addAll(entries);
        currentTotalWeight = totalWeight;
    }

    @VisibleForTesting
    static final class Entry extends AtomicInteger {
        private static final long serialVersionUID = -1719423489992905558L;

        private final Endpoint endpoint;
        final long lowerBound;

        Entry(Endpoint endpoint, long lowerBound) {
            this.endpoint = endpoint;
            this.lowerBound = lowerBound;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        boolean increaseCounter() {
            if (isFull()) {
                return false;
            }
            return incrementAndGet() <= endpoint.weight();
        }

        boolean isFull() {
            return get() >= endpoint.weight();
        }
    }
}
