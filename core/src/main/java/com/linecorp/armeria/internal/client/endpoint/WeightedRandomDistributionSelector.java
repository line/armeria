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
package com.linecorp.armeria.internal.client.endpoint;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.endpoint.WeightedRandomDistributionSelector.AbstractEntry;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * This selector selects an {@link AbstractEntry} using random and the weight of the {@link AbstractEntry}.
 * If there are A(weight 10), B(weight 4) and C(weight 6) {@link AbstractEntry}s, the chances that
 * {@link AbstractEntry}s are selected are 10/20, 4/20 and 6/20, respectively. If {@link AbstractEntry}
 * A is selected 10 times and B and C are not selected as much as their weight, then A is removed temporarily
 * and the chances that B and C are selected are 4/10 and 6/10.
 */
public class WeightedRandomDistributionSelector<T extends AbstractEntry> {

    private final ReentrantLock lock = new ReentrantShortLock();
    private final List<T> allEntries;
    @GuardedBy("lock")
    private final List<T> currentEntries;
    private final long total;
    private long remaining;

    public WeightedRandomDistributionSelector(List<T> endpoints) {
        final ImmutableList.Builder<T> builder = ImmutableList.builderWithExpectedSize(endpoints.size());

        long total = 0;
        for (T entry : endpoints) {
            if (entry.weight() <= 0) {
                continue;
            }
            builder.add(entry);
            total += entry.weight();
        }
        this.total = total;
        remaining = total;
        allEntries = builder.build();
        currentEntries = new ArrayList<>(allEntries);
    }

    @VisibleForTesting
    public List<T> entries() {
        return allEntries;
    }

    @Nullable
    public T select() {
        if (allEntries.isEmpty()) {
            return null;
        }

        final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
        lock.lock();
        try {
            long target = threadLocalRandom.nextLong(remaining);
            final Iterator<T> it = currentEntries.iterator();
            while (it.hasNext()) {
                final T entry = it.next();
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
                    return entry;
                }
            }
        } finally {
            lock.unlock();
        }

        // Since `allEntries` is not empty, should subselect one Endpoint from `allEntries`.
        throw new Error("Should never reach here");
    }

    public abstract static class AbstractEntry {

        private int counter;

        public final void increment() {
            assert counter < weight();
            counter++;
        }

        public abstract int weight();

        public final void reset() {
            counter = 0;
        }

        public final int counter() {
            return counter;
        }

        public final boolean isFull() {
            return counter >= weight();
        }
    }
}
