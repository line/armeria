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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * This selector selects an {@link Endpoint} using random and the weight of the {@link Endpoint}. If there are
 * A(weight 10), B(weight 4) and C(weight 6) {@link Endpoint}s, the chances that {@link Endpoint}s are selected
 * are 10/20, 4/20 and 6/20, respectively. If {@link Endpoint} A is selected 10 times and B and C are not
 * selected as much as their weight, then A is removed temporarily and the chances that B and C are selected
 * are 4/10 and 6/10.
 */
final class WeightedRandomDistributionEndpointSelector {

    private static final AtomicLongFieldUpdater<WeightedRandomDistributionEndpointSelector>
            currentTotalWeightUpdater =
            AtomicLongFieldUpdater.newUpdater(
                    WeightedRandomDistributionEndpointSelector.class, "currentTotalWeight");

    private final List<Entry> entries;
    private final long totalWeight;
    private volatile long currentTotalWeight;

    WeightedRandomDistributionEndpointSelector(List<Endpoint> endpoints) {
        final ImmutableList.Builder<Entry> builder = ImmutableList.builder();

        long totalWeight = 0;
        for (Endpoint endpoint : endpoints) {
            if (endpoint.weight() <= 0) {
                continue;
            }
            builder.add(new Entry(endpoint));
            totalWeight += endpoint.weight();
        }
        this.totalWeight = totalWeight;
        currentTotalWeight = totalWeight;
        entries = builder.build();
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
        final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
        Endpoint selected = null;
        for (;;) {
            final long currentWeight = currentTotalWeight;
            if (currentWeight == 0) {
                // currentTotalWeight will become totalWeight as soon as it becomes 0 so we just loop again.
                continue;
            }
            long nextLong = threadLocalRandom.nextLong(currentWeight);
            // There's a chance that currentTotalWeight is changed before looping currentEntries.
            // However, we have counters and choosing an endpoint doesn't have to be exact so no big deal.
            // TODO(minwoox): Use binary search when the number of endpoints is greater than N.
            for (Entry entry : entries) {
                if (entry.isFull()) {
                    continue;
                }
                final Endpoint endpoint = entry.endpoint();
                final int weight = endpoint.weight();
                nextLong -= weight;
                if (nextLong < 0) {
                    final int counter = entry.incrementAndGet();
                    if (counter <= weight) {
                        selected = endpoint;
                        if (counter == weight) {
                            decreaseCurrentTotalWeight(weight);
                        }
                    }
                    break;
                }
            }

            if (selected != null) {
                return selected;
            }
        }
    }

    private void decreaseCurrentTotalWeight(int weight) {
        final long totalWeight = currentTotalWeightUpdater.addAndGet(this, -1 * weight);
        if (totalWeight == 0) {
            // There's no chance that newWeight is lower than 0 because decreasing the weight of each entry
            // happens only once.
            entries.forEach(entry -> entry.set(0));
            currentTotalWeight = this.totalWeight;
        }
    }

    @VisibleForTesting
    static final class Entry extends AtomicInteger {
        private static final long serialVersionUID = -1719423489992905558L;

        private final Endpoint endpoint;

        Entry(Endpoint endpoint) {
            this.endpoint = endpoint;
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
