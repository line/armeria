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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.EndpointsRampingUpEntry.EndpointAndStep;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.util.concurrent.EventExecutor;

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

    static final WeightRampingUpStrategy INSTANCE =
            new WeightRampingUpStrategy(defaultTransition, () -> CommonPools.workerGroup().next(),
                                        DEFAULT_RAMPING_UP_INTERVAL_MILLIS, DEFAULT_TOTAL_STEPS,
                                        DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS, defaultTicker);

    private final EndpointWeightTransition weightTransition;
    private final Supplier<EventExecutor> executorSupplier;
    private final long rampingUpIntervalMillis;
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
        this.rampingUpIntervalMillis = rampingUpIntervalMillis;
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
        private volatile WeightedRandomDistributionEndpointSelector endpointSelector;

        private final List<Endpoint> endpointsFinishedRampingUp = new ArrayList<>();

        @VisibleForTesting
        final Deque<EndpointsRampingUpEntry> endpointsRampingUp = new ArrayDeque<>();

        @Nullable
        private List<Endpoint> unhandledNewEndpoints;

        RampingUpEndpointWeightSelector(EndpointGroup endpointGroup, EventExecutor executor) {
            super(endpointGroup);
            this.executor = executor;
            final List<Endpoint> initialEndpoints =
                    new ArrayList<>(deduplicateEndpoints(endpointGroup.endpoints()).values());
            endpointSelector = new WeightedRandomDistributionEndpointSelector(initialEndpoints);
            endpointsFinishedRampingUp.addAll(initialEndpoints);
            endpointGroup.addListener(newEndpoints -> {
                // Use the executor so the order of endpoints change is guaranteed.
                executor.execute(() -> updateEndpoints(newEndpoints));
            }, true);
            if (endpointGroup instanceof ListenableAsyncCloseable) {
                ((ListenableAsyncCloseable) endpointGroup).whenClosed().thenRunAsync(this::close, executor);
            }
        }

        /**
         * Removes the duplicate endpoints in the specified {@code newEndpoints} and returns a new map
         * that contains unique endpoints.
         * The value of the map is the {@link Endpoint} whose {@link Endpoint#weight()} is the summed weight of
         * same {@link Endpoint}s.
         */
        private Map<Endpoint, Endpoint> deduplicateEndpoints(List<Endpoint> newEndpoints) {
            final Map<Endpoint, Endpoint> newEndpointsMap = new HashMap<>(newEndpoints.size());

            // The weight of the same endpoints are summed.
            newEndpoints.forEach(
                    newEndpoint -> newEndpointsMap.compute(newEndpoint, (key, v) -> {
                        return v == null ? newEndpoint
                                         : newEndpoint.withWeight(newEndpoint.weight() + v.weight());
                    }));
            return newEndpointsMap;
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
            unhandledNewEndpoints = null;
            if (rampingUpTaskWindowNanos > 0) {
                // Check whether we can ramp up with the previous ramped up endpoints which are at the last
                // of the rampingUpEndpointsEntries.
                if (shouldRampUpWithPreviousRampedUpEntry()) {
                    final Set<EndpointAndStep> newlyAddedEndpoints = filterOldEndpoints(newEndpoints);
                    if (!newlyAddedEndpoints.isEmpty()) {
                        updateWeightAndStep(newlyAddedEndpoints);
                        endpointsRampingUp.getLast().addEndpoints(newlyAddedEndpoints);
                    }
                    buildEndpointSelector();
                    return;
                }

                // Check whether we can ramp up with the next scheduled endpointsRampingUpEntry.
                if (shouldRampUpWithNextScheduledEntry()) {
                    // unhandledNewEndpoints will be ramped up when updateWeightAndStep() is executed.
                    unhandledNewEndpoints = newEndpoints;
                    return;
                }
            }

            final Set<EndpointAndStep> newlyAddedEndpoints = filterOldEndpoints(newEndpoints);
            if (newlyAddedEndpoints.isEmpty()) {
                // newlyAddedEndpoints is empty which means that endpointsFinishedRampingUp are changed.
                // So rebuild the endpoint selector.
                buildEndpointSelector();
                return;
            }

            updateWeightAndStep(newlyAddedEndpoints);

            // Check again because newlyAddedEndpoints can be removed in the updateWeightAndStep method.
            if (!newlyAddedEndpoints.isEmpty()) {
                final ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(
                        this::updateWeightAndStep, rampingUpIntervalMillis,
                        rampingUpIntervalMillis, TimeUnit.MILLISECONDS);
                final EndpointsRampingUpEntry entry = new EndpointsRampingUpEntry(
                        newlyAddedEndpoints, scheduledFuture, ticker, rampingUpIntervalMillis);
                endpointsRampingUp.add(entry);
            }
            buildEndpointSelector();
        }

        private void buildEndpointSelector() {
            final ImmutableList.Builder<Endpoint> targetEndpointsBuilder = ImmutableList.builder();
            targetEndpointsBuilder.addAll(endpointsFinishedRampingUp);
            for (EndpointsRampingUpEntry entry : endpointsRampingUp) {
                for (EndpointAndStep endpointAndStep : entry.endpointAndSteps()) {
                    targetEndpointsBuilder.add(
                            endpointAndStep.endpoint().withWeight(endpointAndStep.currentWeight()));
                }
            }
            endpointSelector = new WeightedRandomDistributionEndpointSelector(targetEndpointsBuilder.build());
        }

        private boolean shouldRampUpWithPreviousRampedUpEntry() {
            final EndpointsRampingUpEntry lastEndpointsRampingUpEntry = endpointsRampingUp.peekLast();
            return lastEndpointsRampingUpEntry != null &&
                   ticker.read() - lastEndpointsRampingUpEntry.lastUpdatedTime <= rampingUpTaskWindowNanos;
        }

        private boolean shouldRampUpWithNextScheduledEntry() {
            final EndpointsRampingUpEntry nextEndpointsRampingUpEntry = endpointsRampingUp.peek();
            return nextEndpointsRampingUpEntry != null &&
                   nextEndpointsRampingUpEntry.nextUpdatingTime - ticker.read() <= rampingUpTaskWindowNanos;
        }

        /**
         * Removes endpoints in endpointsFinishedRampingUp and endpointsRampingUp that
         * newEndpoints do not contain.
         * This also returns the {@link Set} of {@link EndpointAndStep}s whose endpoints are not in
         * in endpointsFinishedRampingUp and endpointsRampingUp.
         */
        private Set<EndpointAndStep> filterOldEndpoints(List<Endpoint> newEndpoints) {
            final Map<Endpoint, Endpoint> newEndpointsMap = deduplicateEndpoints(newEndpoints);

            final List<Endpoint> replacedEndpoints = new ArrayList<>();
            for (final Iterator<Endpoint> i = endpointsFinishedRampingUp.iterator(); i.hasNext();) {
                final Endpoint endpointFinishedRampingUp = i.next();
                final Endpoint newEndpoint = newEndpointsMap.remove(endpointFinishedRampingUp);
                if (newEndpoint == null) {
                    // newEndpoints does not have this endpoint so we remove it.
                    i.remove();
                    continue;
                }

                if (endpointFinishedRampingUp.weight() > newEndpoint.weight()) {
                    // The weight of the new endpoint is lower than the endpoint so we just replace it
                    // because we don't have to ramp up the weight.
                    replacedEndpoints.add(newEndpoint);
                    i.remove();
                } else if (endpointFinishedRampingUp.weight() < newEndpoint.weight()) {
                    // The weight of the new endpoint is greater than the endpoint so we remove the
                    // endpointFinishedRampingUp and put the newEndpoint back.
                    newEndpointsMap.put(newEndpoint, newEndpoint);
                    i.remove();
                } else {
                    // The weights are same so we keep the endpointFinishedRampingUp.
                }
            }
            if (!replacedEndpoints.isEmpty()) {
                endpointsFinishedRampingUp.addAll(replacedEndpoints);
            }

            for (final Iterator<EndpointsRampingUpEntry> i = endpointsRampingUp.iterator();
                 i.hasNext();) {
                final EndpointsRampingUpEntry endpointsRampingUpEntry = i.next();

                final Set<EndpointAndStep> endpointAndSteps = endpointsRampingUpEntry.endpointAndSteps();
                filterOldEndpoints(endpointAndSteps, newEndpointsMap);
                if (endpointAndSteps.isEmpty()) {
                    // All endpointAndSteps are removed so remove the entry completely.
                    i.remove();
                    endpointsRampingUpEntry.scheduledFuture.cancel(true);
                }
            }

            // At this point, newEndpointsMap only contains the new endpoints that have to be ramped up.
            if (newEndpointsMap.isEmpty()) {
                return ImmutableSet.of();
            }
            final Set<EndpointAndStep> newlyAddedEndpoints = new HashSet<>(newEndpointsMap.size());
            newEndpointsMap.values().forEach(
                    endpoint -> newlyAddedEndpoints.add(new EndpointAndStep(endpoint)));
            return newlyAddedEndpoints;
        }

        private void filterOldEndpoints(Set<EndpointAndStep> endpointAndSteps,
                                        Map<Endpoint, Endpoint> newEndpointsMap) {
            final List<EndpointAndStep> replacedEndpoints = new ArrayList<>();
            for (final Iterator<EndpointAndStep> i = endpointAndSteps.iterator(); i.hasNext();) {
                final EndpointAndStep endpointAndStep = i.next();
                final Endpoint rampingUpEndpoint = endpointAndStep.endpoint();
                final Endpoint newEndpoint = newEndpointsMap.remove(rampingUpEndpoint);
                if (newEndpoint == null) {
                    // newEndpointsMap does not contain rampingUpEndpoint so just remove the endpoint.
                    i.remove();
                    continue;
                }

                if (rampingUpEndpoint.weight() == newEndpoint.weight()) {
                    // Same weight so don't do anything. Ramping up happens as it is scheduled.
                } else if (endpointAndStep.currentWeight() > newEndpoint.weight()) {
                    // Don't need to update the weight anymore so we add the newEndpoint to
                    // endpointsFinishedRampingUp and remove it from the iterator.
                    endpointsFinishedRampingUp.add(newEndpoint);
                    i.remove();
                } else {
                    // Should replace the existing endpoint with the new one.
                    final int step = endpointAndStep.step();
                    final EndpointAndStep replaced = new EndpointAndStep(newEndpoint, step);
                    replaced.currentWeight(weightTransition.compute(newEndpoint, step, totalSteps));
                    replacedEndpoints.add(replaced);
                    i.remove();
                }
            }

            if (!replacedEndpoints.isEmpty()) {
                endpointAndSteps.addAll(replacedEndpoints);
            }
        }

        private void updateWeightAndStep() {
            if (unhandledNewEndpoints != null) {
                final Set<EndpointAndStep> newlyAddedEndpoints =
                        filterOldEndpoints(unhandledNewEndpoints);
                final EndpointsRampingUpEntry entry = endpointsRampingUp.peek();
                assert entry != null;
                entry.addEndpoints(newlyAddedEndpoints);
                unhandledNewEndpoints = null;
            }
            final EndpointsRampingUpEntry entry = endpointsRampingUp.poll();
            assert entry != null;

            final Set<EndpointAndStep> endpointAndSteps = entry.endpointAndSteps();
            updateWeightAndStep(endpointAndSteps);
            if (endpointAndSteps.isEmpty()) {
                entry.scheduledFuture.cancel(true);
            } else {
                // Add to the last of the entries.
                endpointsRampingUp.add(entry);
                entry.updateWindowTimestamps();
            }
            buildEndpointSelector();
        }

        private void updateWeightAndStep(Set<EndpointAndStep> endpointAndSteps) {
            for (final Iterator<EndpointAndStep> i = endpointAndSteps.iterator(); i.hasNext();) {
                final EndpointAndStep endpointAndStep = i.next();
                final int step = endpointAndStep.incrementAndGetStep();
                final Endpoint endpoint = endpointAndStep.endpoint();
                if (step == totalSteps) {
                    endpointsFinishedRampingUp.add(endpoint);
                    i.remove();
                } else {
                    final int calculated =
                            weightTransition.compute(endpoint, step, totalSteps);
                    final int currentWeight = Ints.constrainToRange(calculated, 0, endpoint.weight());
                    endpointAndStep.currentWeight(currentWeight);
                }
            }
        }

        private void close() {
            EndpointsRampingUpEntry entry;
            while ((entry = endpointsRampingUp.poll()) != null) {
                entry.scheduledFuture.cancel(true);
            }
        }
    }

    @VisibleForTesting
    static final class EndpointsRampingUpEntry {

        private final Set<EndpointAndStep> endpointAndSteps;
        private final Ticker ticker;
        private final long rampingUpIntervalNanos;

        final ScheduledFuture<?> scheduledFuture;
        long lastUpdatedTime;
        long nextUpdatingTime;

        EndpointsRampingUpEntry(Set<EndpointAndStep> endpointAndSteps, ScheduledFuture<?> scheduledFuture,
                                Ticker ticker, long rampingUpIntervalMillis) {
            this.endpointAndSteps = endpointAndSteps;
            this.scheduledFuture = scheduledFuture;
            this.ticker = ticker;
            rampingUpIntervalNanos = TimeUnit.MILLISECONDS.toNanos(rampingUpIntervalMillis);
            updateWindowTimestamps();
        }

        Set<EndpointAndStep> endpointAndSteps() {
            return endpointAndSteps;
        }

        void addEndpoints(Set<EndpointAndStep> endpoints) {
            endpointAndSteps.addAll(endpoints);
        }

        void updateWindowTimestamps() {
            lastUpdatedTime = ticker.read();
            nextUpdatingTime = lastUpdatedTime + rampingUpIntervalNanos;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("endpointAndSteps", endpointAndSteps)
                              .add("ticker", ticker)
                              .add("rampingUpIntervalNanos", rampingUpIntervalNanos)
                              .add("scheduledFuture", scheduledFuture)
                              .add("lastUpdatedTime", lastUpdatedTime)
                              .add("nextUpdatingTime", nextUpdatingTime)
                              .toString();
        }

        @VisibleForTesting
        static final class EndpointAndStep {

            private final Endpoint endpoint;
            private int step;
            private int currentWeight;

            EndpointAndStep(Endpoint endpoint) {
                this(endpoint, 0);
            }

            EndpointAndStep(Endpoint endpoint, int step) {
                this.endpoint = endpoint;
                this.step = step;
            }

            int incrementAndGetStep() {
                return ++step;
            }

            void currentWeight(int currentWeight) {
                this.currentWeight = currentWeight;
            }

            int currentWeight() {
                return currentWeight;
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
                                  .add("step", step)
                                  .add("currentWeight", currentWeight)
                                  .toString();
            }
        }
    }
}
