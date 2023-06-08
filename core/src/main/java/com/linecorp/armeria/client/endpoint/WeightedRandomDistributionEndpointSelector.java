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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * This selector selects an {@link Endpoint} using random and the weight of the {@link Endpoint}. If there are
 * A(weight 10), B(weight 4) and C(weight 6) {@link Endpoint}s, the chances that {@link Endpoint}s are selected
 * are 10/20, 4/20 and 6/20, respectively. If {@link Endpoint} A is selected 10 times and B and C are not
 * selected as much as their weight, then A is removed temporarily and the chances that B and C are selected
 * are 4/10 and 6/10.
 */
final class WeightedRandomDistributionEndpointSelector {

    private final ReentrantLock lock = new ReentrantShortLock();
    private final List<Entry> allEntries;
    @GuardedBy("lock")
    private final List<Entry> currentEntries;
    private final long total;
    private long remaining;

    WeightedRandomDistributionEndpointSelector(List<Endpoint> endpoints) {
        final ImmutableList.Builder<Entry> builder = ImmutableList.builderWithExpectedSize(endpoints.size());

        long total = 0;
        for (Endpoint endpoint : endpoints) {
            if (endpoint.weight() <= 0) {
                continue;
            }
            builder.add(new Entry(endpoint));
            total += endpoint.weight();
        }
        this.total = total;
        remaining = total;
        allEntries = builder.build();
        currentEntries = new ArrayList<>(allEntries);
    }

    @VisibleForTesting
    List<Entry> entries() {
        return allEntries;
    }

    @Nullable
    Endpoint selectEndpoint() {
        if (allEntries.isEmpty()) {
            return null;
        }

        final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
        lock.lock();
        try {
            long target = threadLocalRandom.nextLong(remaining);
            final Iterator<Entry> it = currentEntries.iterator();
            while (it.hasNext()) {
                final Entry entry = it.next();
                final int weight = entry.weight();
                target -= weight;
                if (target < 0) {
                    entry.increment();
                    if (entry.isFull()) {
                        it.remove();
                        entry.reset();
                        remaining -= weight;
                        if (remaining == 0) {
                            // As all entries are full, reset `currentEntries` and `remaining`.
                            currentEntries.addAll(allEntries);
                            remaining = total;
                        } else {
                            assert remaining > 0 : remaining;
                        }
                    }
                    return entry.endpoint();
                }
            }
        } finally {
            lock.unlock();
        }

        // Since `allEntries` is not empty, should select one Endpoint from `allEntries`.
        throw new Error("Should never reach here");
    }

    @VisibleForTesting
    static final class Entry {

        private final Endpoint endpoint;
        private int counter;

        Entry(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        void increment() {
            assert counter < endpoint().weight();
            counter++;
        }

        int weight() {
            return endpoint().weight();
        }

        void reset() {
            counter = 0;
        }

        @VisibleForTesting
        int counter() {
            return counter;
        }

        boolean isFull() {
            return counter >= endpoint.weight();
        }
    }
}
