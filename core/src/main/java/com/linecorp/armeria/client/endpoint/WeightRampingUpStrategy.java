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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyBuilder.DEFAULT_RAMPING_UP_INTERVAL_MILLIS;
import static com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyBuilder.DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS;
import static com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyBuilder.DEFAULT_TOTAL_STEPS;
import static com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyBuilder.defaultTransition;
import static com.linecorp.armeria.internal.client.endpoint.RampingUpKeys.createdAtNanos;
import static com.linecorp.armeria.internal.client.endpoint.RampingUpKeys.hasCreatedAtNanos;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.math.IntMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.EndpointsRampingUpEntry.EndpointAndStep;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.util.concurrent.EventExecutor;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * A ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
 * {@link Endpoint}s using {@link EndpointWeightTransition},
 * {@code rampingUpIntervalMillis} and {@code rampingUpTaskWindow}.
 * If more than one {@link Endpoint} are added within the {@code rampingUpTaskWindow}, the weights of
 * them are updated together. If there's already a scheduled job and new {@link Endpoint}s are added
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
final class WeightRampingUpStrategy implements EndpointSelectionStrategy {

    private static final Ticker defaultTicker = Ticker.systemTicker();
    private static final WeightedRandomDistributionEndpointSelector EMPTY_SELECTOR =
            new WeightedRandomDistributionEndpointSelector(ImmutableList.of());

    static final WeightRampingUpStrategy INSTANCE =
            new WeightRampingUpStrategy(defaultTransition, () -> CommonPools.workerGroup().next(),
                                        DEFAULT_RAMPING_UP_INTERVAL_MILLIS, DEFAULT_TOTAL_STEPS,
                                        DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS, defaultTicker);

    private final EndpointWeightTransition weightTransition;
    private final Supplier<EventExecutor> executorSupplier;
    private final long rampingUpIntervalNanos;
    private final int totalSteps;
    private final long rampingUpTaskWindowNanos;
    private final Ticker ticker;

    WeightRampingUpStrategy(EndpointWeightTransition weightTransition,
                            Supplier<EventExecutor> executorSupplier, long rampingUpIntervalMillis,
                            int totalSteps, long rampingUpTaskWindowMillis) {
        this(weightTransition, executorSupplier, rampingUpIntervalMillis, totalSteps,
             rampingUpTaskWindowMillis, defaultTicker);
    }

    @VisibleForTesting
    WeightRampingUpStrategy(EndpointWeightTransition weightTransition,
                            Supplier<EventExecutor> executorSupplier, long rampingUpIntervalMillis,
                            int totalSteps, long rampingUpTaskWindowMillis, Ticker ticker) {
        this.weightTransition = requireNonNull(weightTransition, "weightTransition");
        this.executorSupplier = requireNonNull(executorSupplier, "executorSupplier");
        checkArgument(rampingUpIntervalMillis > 0,
                      "rampingUpIntervalMillis: %s (expected: > 0)", rampingUpIntervalMillis);
        rampingUpIntervalNanos = TimeUnit.MILLISECONDS.toNanos(rampingUpIntervalMillis);
        checkArgument(totalSteps > 0, "totalSteps: %s (expected: > 0)", totalSteps);
        this.totalSteps = totalSteps;
        checkArgument(rampingUpTaskWindowMillis >= 0,
                      "rampingUpTaskWindowMillis: %s (expected: > 0)",
                      rampingUpTaskWindowMillis);
        rampingUpTaskWindowNanos = TimeUnit.MILLISECONDS.toNanos(rampingUpTaskWindowMillis);
        this.ticker = requireNonNull(ticker, "ticker");
    }

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new RampingUpEndpointWeightSelector(endpointGroup, executorSupplier.get());
    }

    @VisibleForTesting
    final class RampingUpEndpointWeightSelector extends AbstractEndpointSelector {

        private final EventExecutor executor;
        private volatile WeightedRandomDistributionEndpointSelector endpointSelector = EMPTY_SELECTOR;

        private final List<Endpoint> endpointsFinishedRampingUp = new ArrayList<>();

        @VisibleForTesting
        final Deque<EndpointsRampingUpEntry> endpointsRampingUp = new ArrayDeque<>();
        @VisibleForTesting
        final Map<Long, EndpointsRampingUpEntry> rampingUpWindowsMap = new HashMap<>();
        private Object2LongOpenHashMap<Endpoint> endpointCreatedTimestamps = new Object2LongOpenHashMap<>();

        RampingUpEndpointWeightSelector(EndpointGroup endpointGroup, EventExecutor executor) {
            super(endpointGroup);
            this.executor = executor;
            if (endpointGroup instanceof ListenableAsyncCloseable) {
                ((ListenableAsyncCloseable) endpointGroup).whenClosed().thenRunAsync(this::close, executor);
            }
            initialize();
        }

        @Override
        protected CompletableFuture<Void> updateNewEndpoints(List<Endpoint> endpoints) {
            // Use the executor so the order of endpoints change is guaranteed.
            return CompletableFuture.runAsync(() -> updateEndpoints(endpoints), executor);
        }

        private long computeCreateTimestamp(Endpoint endpoint) {
            if (hasCreatedAtNanos(endpoint)) {
                return createdAtNanos(endpoint);
            }
            if (endpointCreatedTimestamps.containsKey(endpoint)) {
                return endpointCreatedTimestamps.getLong(endpoint);
            }
            return ticker.read();
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            return endpointSelector.selectEndpoint();
        }

        @VisibleForTesting
        WeightedRandomDistributionEndpointSelector endpointSelector() {
            return endpointSelector;
        }

        // Only executed by the executor.
        private void updateEndpoints(List<Endpoint> newEndpoints) {

            // clean up existing entries
            for (EndpointsRampingUpEntry entry : rampingUpWindowsMap.values()) {
                entry.endpointAndSteps().clear();
            }
            endpointsFinishedRampingUp.clear();

            // We add the new endpoints from this point
            final Object2LongOpenHashMap<Endpoint> newCreatedTimestamps = new Object2LongOpenHashMap<>();
            for (Endpoint endpoint : newEndpoints) {
                // Set the cached created timestamps for the next iteration
                final long createTimestamp = computeCreateTimestamp(endpoint);
                newCreatedTimestamps.put(endpoint, createTimestamp);

                // check if the endpoint is already finished ramping up
                final int step = numStep(rampingUpIntervalNanos, ticker, createTimestamp);
                if (step >= totalSteps) {
                    endpointsFinishedRampingUp.add(endpoint);
                    continue;
                }

                // Create a EndpointsRampingUpEntry if there isn't one already
                final long window = windowIndex(createTimestamp);
                if (!rampingUpWindowsMap.containsKey(window)) {
                    // align the schedule to execute at the start of each window
                    final long initialDelayNanos = initialDelayNanos(window);
                    final ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(
                            () -> updateWeightAndStep(window), initialDelayNanos,
                            rampingUpIntervalNanos, TimeUnit.NANOSECONDS);
                    final EndpointsRampingUpEntry entry = new EndpointsRampingUpEntry(
                            new HashSet<>(), scheduledFuture, ticker, rampingUpIntervalNanos);
                    rampingUpWindowsMap.put(window, entry);
                }
                final EndpointsRampingUpEntry rampingUpEntry = rampingUpWindowsMap.get(window);

                final EndpointAndStep endpointAndStep =
                        new EndpointAndStep(endpoint, weightTransition, step, totalSteps);
                rampingUpEntry.addEndpoint(endpointAndStep);
            }
            endpointCreatedTimestamps = newCreatedTimestamps;

            buildEndpointSelector();
        }

        private void buildEndpointSelector() {
            final ImmutableList.Builder<Endpoint> targetEndpointsBuilder = ImmutableList.builder();
            targetEndpointsBuilder.addAll(endpointsFinishedRampingUp);
            for (EndpointsRampingUpEntry entry : rampingUpWindowsMap.values()) {
                for (EndpointAndStep endpointAndStep : entry.endpointAndSteps()) {
                    targetEndpointsBuilder.add(
                            endpointAndStep.endpoint().withWeight(endpointAndStep.currentWeight()));
                }
            }
            endpointSelector = new WeightedRandomDistributionEndpointSelector(targetEndpointsBuilder.build());
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
            final EndpointsRampingUpEntry entry = rampingUpWindowsMap.get(window);
            assert entry != null;
            final Set<EndpointAndStep> endpointAndSteps = entry.endpointAndSteps();
            updateWeightAndStep(endpointAndSteps);
            if (endpointAndSteps.isEmpty()) {
                rampingUpWindowsMap.remove(window).scheduledFuture.cancel(true);
            }
            buildEndpointSelector();
        }

        private void updateWeightAndStep(Set<EndpointAndStep> endpointAndSteps) {
            for (final Iterator<EndpointAndStep> i = endpointAndSteps.iterator(); i.hasNext();) {
                final EndpointAndStep endpointAndStep = i.next();
                final int step = endpointAndStep.incrementAndGetStep();
                final Endpoint endpoint = endpointAndStep.endpoint();
                if (step >= totalSteps) {
                    endpointsFinishedRampingUp.add(endpoint);
                    i.remove();
                }
            }
        }

        private void close() {
            rampingUpWindowsMap.values().forEach(e -> e.scheduledFuture.cancel(true));
        }
    }

    private static int numStep(long rampingUpIntervalNanos, Ticker ticker, long createTimestamp) {
        final long timePassed = ticker.read() - createTimestamp;
        final int step = Ints.saturatedCast(timePassed / rampingUpIntervalNanos);
        // there's no point in having an endpoint at step 0 (no weight), so we increment by 1
        return IntMath.saturatedAdd(step, 1);
    }

    @VisibleForTesting
    static final class EndpointsRampingUpEntry {

        private final Set<EndpointAndStep> endpointAndSteps;
        private final Ticker ticker;
        private final long rampingUpIntervalNanos;

        final ScheduledFuture<?> scheduledFuture;

        EndpointsRampingUpEntry(Set<EndpointAndStep> endpointAndSteps, ScheduledFuture<?> scheduledFuture,
                                Ticker ticker, long rampingUpIntervalMillis) {
            this.endpointAndSteps = endpointAndSteps;
            this.scheduledFuture = scheduledFuture;
            this.ticker = ticker;
            rampingUpIntervalNanos = TimeUnit.MILLISECONDS.toNanos(rampingUpIntervalMillis);
        }

        Set<EndpointAndStep> endpointAndSteps() {
            return endpointAndSteps;
        }

        void addEndpoint(EndpointAndStep endpoint) {
            endpointAndSteps.add(endpoint);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("endpointAndSteps", endpointAndSteps)
                              .add("ticker", ticker)
                              .add("rampingUpIntervalNanos", rampingUpIntervalNanos)
                              .add("scheduledFuture", scheduledFuture)
                              .toString();
        }

        @VisibleForTesting
        static final class EndpointAndStep {

            private final Endpoint endpoint;
            private final EndpointWeightTransition weightTransition;
            private int step;
            private final int totalSteps;
            private int currentWeight;

            EndpointAndStep(Endpoint endpoint, EndpointWeightTransition weightTransition,
                            int step, int totalSteps) {
                this.endpoint = endpoint;
                this.weightTransition = weightTransition;
                this.step = step;
                this.totalSteps = totalSteps;
            }

            int incrementAndGetStep() {
                return ++step;
            }

            int currentWeight() {
                return currentWeight = computeWeight(endpoint, step);
            }

            private int computeWeight(Endpoint endpoint, int step) {
                final int calculated = weightTransition.compute(endpoint, step, totalSteps);
                return Ints.constrainToRange(calculated, 0, endpoint.weight());
            }

            int step() {
                return step;
            }

            Endpoint endpoint() {
                return endpoint;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                                  .add("endpoint", endpoint)
                                  .add("currentWeight", currentWeight)
                                  .add("weightTransition", weightTransition)
                                  .add("step", step)
                                  .add("totalSteps", totalSteps)
                                  .toString();
            }
        }
    }
}
