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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.math.IntMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Ticker;
import com.linecorp.armeria.internal.common.loadbalancer.WeightedObject;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.netty.util.concurrent.EventExecutor;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * A ramping up {@link LoadBalancer} which ramps the weight of newly added
 * candidates using {@link WeightTransition}, {@code rampingUpIntervalMillis} and {@code rampingUpTaskWindow}.
 * If more than one candidate are added within the {@code rampingUpTaskWindow}, the weights of
 * them are updated together. If there's already a scheduled job and new candidates are added
 * within the {@code rampingUpTaskWindow}, they are updated together.
 * This is an example of how it works when {@code rampingUpTaskWindow} is 500 milliseconds and
 * {@code rampingUpIntervalMillis} is 2000 milliseconds:
 * <pre>{@code
 * ----------------------------------------------------------------------------------------------------
 *     A         B                             C                                       D
 *     t0        t1                            t2                                      t3         t4
 * ----------------------------------------------------------------------------------------------------
 *     0ms       t0 + 200ms                    t0 + 1000ms                          t0 + 1800ms  t0 + 2000ms
 * }</pre>
 * A and B are ramped up right away when they are added and they are ramped up together at t4.
 * C is updated alone every 2000 milliseconds. D is ramped up together with A and B at t4.
 */
final class RampingUpLoadBalancer<T> implements UpdatableLoadBalancer<T> {

    private static final Logger logger = LoggerFactory.getLogger(RampingUpLoadBalancer.class);
    private static final SimpleLoadBalancer<?> EMPTY_RANDOM_LOAD_BALANCER =
            LoadBalancer.ofWeightedRandom(ImmutableList.of(), x -> 0);

    private final long rampingUpIntervalNanos;
    private final int totalSteps;
    private final long rampingUpTaskWindowNanos;
    private final Ticker ticker;
    private final WeightTransition<T> weightTransition;
    @Nullable
    private final ToIntFunction<T> weightFunction;
    private final Function<T, Long> timestampFunction;

    private final EventExecutor executor;
    private final ReentrantShortLock lock = new ReentrantShortLock(true);

    @SuppressWarnings("unchecked")
    private volatile SimpleLoadBalancer<Weighted> weightedRandomLoadBalancer =
            (SimpleLoadBalancer<Weighted>) EMPTY_RANDOM_LOAD_BALANCER;

    private final List<Weighted> candidatesFinishedRampingUp = new ArrayList<>();

    @VisibleForTesting
    final Map<Long, CandidatesRampingUpEntry<T>> rampingUpWindowsMap = new HashMap<>();
    private Object2LongOpenHashMap<T> candidateCreatedTimestamps = new Object2LongOpenHashMap<>();

    RampingUpLoadBalancer(Iterable<T> candidates, @Nullable ToIntFunction<T> weightFunction,
                          long rampingUpIntervalMillis, int totalSteps, long rampingUpTaskWindowMillis,
                          WeightTransition<T> weightTransition, Function<T, Long> timestampFunction,
                          Ticker ticker, EventExecutor executor) {
        rampingUpIntervalNanos = TimeUnit.MILLISECONDS.toNanos(rampingUpIntervalMillis);
        this.totalSteps = totalSteps;
        rampingUpTaskWindowNanos = TimeUnit.MILLISECONDS.toNanos(rampingUpTaskWindowMillis);
        this.ticker = ticker;
        this.weightTransition = weightTransition;
        this.weightFunction = weightFunction;
        this.timestampFunction = timestampFunction;
        this.executor = executor;
        updateCandidates(candidates);
    }

    @Nullable
    @Override
    public T pick() {
        final SimpleLoadBalancer<Weighted> loadBalancer = weightedRandomLoadBalancer;
        final Weighted weighted = loadBalancer.pick();
        if (weighted == null) {
            return null;
        }
        if (weighted instanceof WeightedObject) {
            //noinspection unchecked
            return ((WeightedObject<T>) weighted).get();
        } else {
            //noinspection unchecked
            return (T) weighted;
        }
    }

    @Override
    public void updateCandidates(Iterable<? extends T> candidates) {
        lock.lock();
        try {
            updateCandidates0(ImmutableList.copyOf(candidates));
        } finally {
            lock.unlock();
        }
    }

    private void updateCandidates0(List<T> newCandidates) {
        // clean up existing entries
        for (CandidatesRampingUpEntry<T> entry : rampingUpWindowsMap.values()) {
            entry.candidateAndSteps().clear();
        }
        candidatesFinishedRampingUp.clear();

        // We add the new candidates from this point
        final Object2LongOpenHashMap<T> newCreatedTimestamps = new Object2LongOpenHashMap<>();
        for (T candidate : newCandidates) {
            // Set the cached created timestamps for the next iteration
            final long createTimestamp = computeCreateTimestamp(candidate);
            newCreatedTimestamps.put(candidate, createTimestamp);

            // check if the candidate is already finished ramping up
            final int step = numStep(rampingUpIntervalNanos, ticker, createTimestamp);
            if (step >= totalSteps) {
                candidatesFinishedRampingUp.add(toWeighted(candidate, weightFunction));
                continue;
            }

            // Create a CandidatesRampingUpEntry if there isn't one already
            final long window = windowIndex(createTimestamp);
            if (!rampingUpWindowsMap.containsKey(window)) {
                // align the schedule to execute at the start of each window
                final long initialDelayNanos = initialDelayNanos(window);
                final ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(
                        () -> updateWeightAndStep(window), initialDelayNanos,
                        rampingUpIntervalNanos, TimeUnit.NANOSECONDS);
                final CandidatesRampingUpEntry<T> entry =
                        new CandidatesRampingUpEntry<>(new HashSet<>(), scheduledFuture);
                rampingUpWindowsMap.put(window, entry);
            }
            final CandidatesRampingUpEntry<T> rampingUpEntry = rampingUpWindowsMap.get(window);

            final CandidateAndStep<T> candidateAndStep =
                    new CandidateAndStep<>(candidate, weightFunction, weightTransition, step, totalSteps);
            rampingUpEntry.addCandidate(candidateAndStep);
        }
        candidateCreatedTimestamps = newCreatedTimestamps;

        buildLoadBalancer();
    }

    private long computeCreateTimestamp(T candidate) {
        final Long timestamp;
        try {
            timestamp = timestampFunction.apply(candidate);
        } catch (Exception e) {
            logger.warn("Failed to compute the create timestamp for candidate: {}", candidate, e);
            return ticker.read();
        }

        if (timestamp != null) {
            return timestamp;
        }
        if (candidateCreatedTimestamps.containsKey(candidate)) {
            return candidateCreatedTimestamps.getLong(candidate);
        }
        return ticker.read();
    }

    private void buildLoadBalancer() {
        final ImmutableList.Builder<Weighted> targetCandidatesBuilder = ImmutableList.builder();
        targetCandidatesBuilder.addAll(candidatesFinishedRampingUp);
        for (CandidatesRampingUpEntry<T> entry : rampingUpWindowsMap.values()) {
            for (CandidateAndStep<T> candidateAndStep : entry.candidateAndSteps()) {
                targetCandidatesBuilder.add(
                        // Capture the current weight of the candidate for the current step.
                        new WeightedObject<>(candidateAndStep.candidate(), candidateAndStep.currentWeight()));
            }
        }
        final List<Weighted> candidates = targetCandidatesBuilder.build();
        if (rampingUpWindowsMap.isEmpty()) {
            logger.info("Finished ramping up. candidates: {}", candidates);
        } else {
            logger.debug("Ramping up. candidates: {}", candidates);
        }

        boolean found = false;
        for (Weighted candidate : candidates) {
            if (candidate.weight() > 0) {
                found = true;
                break;
            }
        }
        if (!found) {
            logger.warn("No valid candidate with weight > 0. candidates: {}", candidates);
        }
        weightedRandomLoadBalancer = LoadBalancer.ofWeightedRandom(candidates);
    }

    @VisibleForTesting
    SimpleLoadBalancer<Weighted> weightedRandomLoadBalancer() {
        return weightedRandomLoadBalancer;
    }

    @VisibleForTesting
    long windowIndex(long timestamp) {
        long window = timestamp % rampingUpIntervalNanos;
        if (rampingUpTaskWindowNanos > 0) {
            window /= rampingUpTaskWindowNanos;
        }
        return window;
    }

    private long initialDelayNanos(long windowIndex) {
        final long timestamp = ticker.read();
        final long base = (timestamp / rampingUpIntervalNanos + 1) * rampingUpIntervalNanos;
        final long nextTimestamp = base + windowIndex * rampingUpTaskWindowNanos;
        return nextTimestamp - timestamp;
    }

    private void updateWeightAndStep(long window) {
        lock.lock();
        try {
            updateWeightAndStep0(window);
        } finally {
            lock.unlock();
        }
    }

    private void updateWeightAndStep0(long window) {
        final CandidatesRampingUpEntry<T> entry = rampingUpWindowsMap.get(window);
        assert entry != null;
        final Set<CandidateAndStep<T>> candidateAndSteps = entry.candidateAndSteps();
        updateWeightAndStep0(candidateAndSteps);
        if (candidateAndSteps.isEmpty()) {
            rampingUpWindowsMap.remove(window).scheduledFuture.cancel(true);
        }
        buildLoadBalancer();
    }

    private void updateWeightAndStep0(Set<CandidateAndStep<T>> candidateAndSteps) {
        for (final Iterator<CandidateAndStep<T>> i = candidateAndSteps.iterator(); i.hasNext();) {
            final CandidateAndStep<T> candidateAndStep = i.next();
            final int step = candidateAndStep.incrementAndGetStep();
            final Weighted candidate = candidateAndStep.weighted();
            if (step >= totalSteps) {
                candidatesFinishedRampingUp.add(candidate);
                i.remove();
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            rampingUpWindowsMap.values().forEach(e -> e.scheduledFuture.cancel(true));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("weightedRandomLoadBalancer", weightedRandomLoadBalancer)
                          .add("candidatesFinishedRampingUp", candidatesFinishedRampingUp)
                          .add("rampingUpWindowsMap", rampingUpWindowsMap)
                          .toString();
    }

    private static int numStep(long rampingUpIntervalNanos, Ticker ticker, long createTimestamp) {
        final long timePassed = ticker.read() - createTimestamp;
        final int step = Ints.saturatedCast(timePassed / rampingUpIntervalNanos);
        // there's no point in having an candidate at step 0 (no weight), so we increment by 1
        return IntMath.saturatedAdd(step, 1);
    }

    @VisibleForTesting
    static final class CandidatesRampingUpEntry<T> {

        private final Set<CandidateAndStep<T>> candidateAndSteps;
        final ScheduledFuture<?> scheduledFuture;

        CandidatesRampingUpEntry(Set<CandidateAndStep<T>> candidateAndSteps,
                                 ScheduledFuture<?> scheduledFuture) {
            this.candidateAndSteps = candidateAndSteps;
            this.scheduledFuture = scheduledFuture;
        }

        Set<CandidateAndStep<T>> candidateAndSteps() {
            return candidateAndSteps;
        }

        void addCandidate(CandidateAndStep<T> candidate) {
            candidateAndSteps.add(candidate);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("candidateAndSteps", candidateAndSteps)
                              .add("scheduledFuture", scheduledFuture)
                              .toString();
        }
    }

    private static <T> Weighted toWeighted(T candidate, @Nullable ToIntFunction<T> weightFunction) {
        if (weightFunction == null) {
            return (Weighted) candidate;
        } else {
            return new WeightedObject<>(candidate, weightFunction.applyAsInt(candidate));
        }
    }

    @VisibleForTesting
    static final class CandidateAndStep<T> {
        private final T candidate;
        private final Weighted weighted;
        private final WeightTransition<T> weightTransition;
        private int step;
        private final int totalSteps;
        private int currentWeight;

        CandidateAndStep(T candidate, @Nullable ToIntFunction<T> weightFunction,
                         WeightTransition<T> weightTransition, int step, int totalSteps) {
            this.candidate = candidate;
            weighted = toWeighted(candidate, weightFunction);
            this.weightTransition = weightTransition;
            this.step = step;
            this.totalSteps = totalSteps;
        }

        int incrementAndGetStep() {
            return ++step;
        }

        int currentWeight() {
            return currentWeight = computeWeight();
        }

        private int computeWeight() {
            final int originalWeight = weighted.weight();
            final int calculated = weightTransition.compute(candidate, originalWeight, step, totalSteps);
            return Ints.constrainToRange(calculated, 0, originalWeight);
        }

        int step() {
            return step;
        }

        Weighted weighted() {
            return weighted;
        }

        T candidate() {
            return candidate;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("candidate", candidate)
                              .add("currentWeight", currentWeight)
                              .add("weightTransition", weightTransition)
                              .add("step", step)
                              .add("totalSteps", totalSteps)
                              .toString();
        }
    }
}
