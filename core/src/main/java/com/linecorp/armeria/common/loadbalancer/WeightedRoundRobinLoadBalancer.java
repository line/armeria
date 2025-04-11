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

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.loadbalancer.WeightedObject;

/**
 * A weighted round robin select strategy.
 *
 * <p>For example, with node a, b and c:
 * <ul>
 *   <li>if endpoint weights are 1,1,1 (or 2,2,2), then select result is abc abc ...</li>
 *   <li>if endpoint weights are 1,2,3 (or 2,4,6), then select result is abcbcc(or abcabcbcbccc) ...</li>
 *   <li>if endpoint weights are 3,5,7, then select result is abcabcabcbcbccc abcabcabcbcbccc ...</li>
 * </ul>
 */
final class WeightedRoundRobinLoadBalancer<T> implements SimpleLoadBalancer<T> {

    private static final Logger logger = LoggerFactory.getLogger(WeightedRoundRobinLoadBalancer.class);

    private final AtomicInteger sequence = new AtomicInteger();
    private final CandidatesAndWeights<T> candidatesAndWeights;

    WeightedRoundRobinLoadBalancer(Iterable<T> candidates,
                                   @Nullable ToIntFunction<T> weightFunction) {
        candidatesAndWeights = new CandidatesAndWeights<>(candidates, weightFunction);
    }

    @Nullable
    @Override
    public T pick() {
        return candidatesAndWeights.select(sequence.getAndIncrement());
    }

    // endpoints accumulation which are grouped by weight
    private static final class CandidatesGroupByWeight {
        final long startIndex;
        final int weight;
        final long accumulatedWeight;

        CandidatesGroupByWeight(long startIndex, int weight, long accumulatedWeight) {
            this.startIndex = startIndex;
            this.weight = weight;
            this.accumulatedWeight = accumulatedWeight;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("startIndex", startIndex)
                              .add("weight", weight)
                              .add("accumulatedWeight", accumulatedWeight)
                              .toString();
        }
    }

    //
    // In general, assume the weights are w0 < w1 < ... < wM where M = N - 1, N is number of endpoints.
    //
    // * The first part of result: (a0..aM)(a0..aM)...(a0..aM) [w0 times for N elements].
    // * The second part of result: (a1..aM)...(a1..aM) [w1 - w0 times for N - 1 elements].
    // * and so on
    //
    // In this way:
    //
    // * Total number of elements of first part is: X(0) = w0 * N.
    // * Total number of elements of second part is: X(1) = (w1 - w0) * (N - 1)
    // * and so on
    //
    // Therefore, to find endpoint for a sequence S = currentSequence % totalWeight, firstly we find
    // the part which sequence belongs, and then modular by the number of elements in this part.
    //
    // Accumulation function F:
    //
    // * F(0) = X(0)
    // * F(1) = X(0) + X(1)
    // * F(2) = X(0) + X(1) + X(2)
    // * F(i) = F(i-1) + X(i)
    //
    // We could easily find the part (which sequence S belongs) using binary search on F.
    // Just find the index k where:
    //
    //                               F(k) <= S < F(k + 1).
    //
    // So, S belongs to part number (k + 1), index of the sequence in this part is P = S - F(k).
    // Because part (k + 1) start at index (k + 1), and contains (N - k - 1) elements,
    // then the real index is:
    //
    //                              (k + 1) + (P % (N - k - 1))
    //
    // For special case like w(i) == w(i+1). We just group them all together
    // and mark the start index of the group.
    //
    private static final class CandidatesAndWeights<T> {
        private final List<Weighted> candidates;
        private final boolean weighted;
        private final long totalWeight; // prevent overflow by using long
        private final List<CandidatesGroupByWeight> accumulatedGroups;

        CandidatesAndWeights(Iterable<T> candidates0, @Nullable ToIntFunction<T> weightFunction) {
            // prepare immutable candidates
            candidates = Streams.stream(candidates0)
                                .map(e -> {
                                    if (weightFunction == null) {
                                        return (Weighted) e;
                                    } else {
                                        return new WeightedObject<>(e, weightFunction.applyAsInt(e));
                                    }
                                })
                                .filter(e -> e.weight() > 0) // only process candidate with weight > 0
                                .sorted(Comparator.comparing(Weighted::weight))
                                .collect(toImmutableList());
            final long numCandidates = candidates.size();

            if (numCandidates == 0 && !Iterables.isEmpty(candidates0)) {
                logger.warn("No valid candidate with weight > 0. candidates: {}", candidates);
            }

            // get min weight, max weight and number of distinct weight
            int minWeight = Integer.MAX_VALUE;
            int maxWeight = Integer.MIN_VALUE;
            int numberDistinctWeight = 0;

            int oldWeight = -1;
            for (Weighted candidate : candidates) {
                final int weight = candidate.weight();
                minWeight = Math.min(minWeight, weight);
                maxWeight = Math.max(maxWeight, weight);
                numberDistinctWeight += weight == oldWeight ? 0 : 1;
                oldWeight = weight;
            }

            // accumulation
            long totalWeight = 0;

            final ImmutableList.Builder<CandidatesGroupByWeight>
                    accumulatedGroupsBuilder =
                    ImmutableList.builderWithExpectedSize(numberDistinctWeight);
            CandidatesGroupByWeight currentGroup = null;

            long rest = numCandidates;
            for (Weighted candidate : candidates) {
                if (currentGroup == null || currentGroup.weight != candidate.weight()) {
                    totalWeight += currentGroup == null ? candidate.weight() * rest
                                                        : (candidate.weight() - currentGroup.weight) * rest;
                    currentGroup = new CandidatesGroupByWeight(
                            numCandidates - rest, candidate.weight(), totalWeight);
                    accumulatedGroupsBuilder.add(currentGroup);
                }

                rest--;
            }

            accumulatedGroups = accumulatedGroupsBuilder.build();
            this.totalWeight = totalWeight;
            weighted = minWeight != maxWeight;
        }

        @SuppressWarnings("unchecked")
        @Nullable
        T select(int currentSequence) {
            final Weighted selected = select0(currentSequence);
            if (selected instanceof WeightedObject<?>) {
                return ((WeightedObject<T>) selected).get();
            } else {
                return (T) selected;
            }
        }

        @Nullable
        Weighted select0(int currentSequence) {
            if (candidates.isEmpty()) {
                return null;
            }

            if (weighted) {
                final long numberCandidates = candidates.size();

                final long mod = Math.abs(currentSequence % totalWeight);

                if (mod < accumulatedGroups.get(0).accumulatedWeight) {
                    return candidates.get((int) (mod % numberCandidates));
                }

                int left = 0;
                int right = accumulatedGroups.size() - 1;
                int mid;
                while (left < right) {
                    mid = left + ((right - left) >> 1);

                    if (mid == left) {
                        break;
                    }

                    if (accumulatedGroups.get(mid).accumulatedWeight <= mod) {
                        left = mid;
                    } else {
                        right = mid;
                    }
                }

                // (left + 1) is the part where sequence belongs
                final long indexInPart = mod - accumulatedGroups.get(left).accumulatedWeight;
                final long startIndex = accumulatedGroups.get(left + 1).startIndex;
                return candidates.get((int) (startIndex + indexInPart % (numberCandidates - startIndex)));
            }

            return candidates.get(Math.abs(currentSequence % candidates.size()));
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("candidates", candidates)
                              .add("weighted", weighted)
                              .add("totalWeight", totalWeight)
                              .add("accumulatedGroups", accumulatedGroups)
                              .toString();
        }
    }
}
