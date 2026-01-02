/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.loadbalancer;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Streams;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.internal.common.loadbalancer.WeightedObject;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * This {@link LoadBalancer} selects an element using random and {@link WeightedObject#weight()}.
 * If there are A(weight 10), B(weight 4) and C(weight 6) elements, the chances that
 * elements are selected are 10/20, 4/20 and 6/20, respectively. If A is selected 10 times and B and C are not
 * selected as much as their weight, then A is removed temporarily and the chances that B and C are selected are
 * 4/10 and 6/10.
 */
final class WeightedRandomLoadBalancer<T> implements SimpleLoadBalancer<T> {

    private final ReentrantLock lock = new ReentrantShortLock();
    private final List<CandidateContext<T>> allEntries;
    @GuardedBy("lock")
    private final List<CandidateContext<T>> currentEntries;
    private final long total;
    private long remaining;

    WeightedRandomLoadBalancer(Iterable<? extends T> candidates,
                               @Nullable ToIntFunction<? super T> weightFunction) {
        @SuppressWarnings("unchecked")
        final List<CandidateContext<T>> candidateContexts =
                Streams.stream((Iterable<T>) candidates)
                       .map(e -> {
                           if (weightFunction == null) {
                               return new CandidateContext<>(e, ((Weighted) e).weight());
                           } else {
                               return new CandidateContext<>(e, weightFunction.applyAsInt(e));
                           }
                       })
                       .filter(e -> e.weight() > 0)
                       .collect(toImmutableList());

        total = candidateContexts.stream().mapToLong(CandidateContext::weight).sum();
        remaining = total;
        allEntries = candidateContexts;
        currentEntries = new ArrayList<>(allEntries);
    }

    @VisibleForTesting
    List<CandidateContext<T>> entries() {
        return allEntries;
    }

    @Nullable
    @Override
    public T pick() {
        if (allEntries.isEmpty()) {
            return null;
        }

        final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
        lock.lock();
        try {
            long target = threadLocalRandom.nextLong(remaining);
            final Iterator<CandidateContext<T>> it = currentEntries.iterator();
            while (it.hasNext()) {
                final CandidateContext<T> entry = it.next();
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
                    return entry.get();
                }
            }
        } finally {
            lock.unlock();
        }

        // Since `allEntries` is not empty, should subselect one Endpoint from `allEntries`.
        throw new Error("Should never reach here");
    }

    @SuppressWarnings("GuardedBy")
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("allEntries", allEntries)
                          .add("currentEntries", currentEntries)
                          .add("total", total)
                          .add("remaining", remaining)
                          .toString();
    }

    @VisibleForTesting
    static final class CandidateContext<T> extends WeightedObject<T> {

        private int counter;

        CandidateContext(T candidate, int weight) {
            super(candidate, weight);
        }

        void increment() {
            assert counter < weight();
            counter++;
        }

        void reset() {
            counter = 0;
        }

        int counter() {
            return counter;
        }

        boolean isFull() {
            return counter >= weight();
        }
    }
}
