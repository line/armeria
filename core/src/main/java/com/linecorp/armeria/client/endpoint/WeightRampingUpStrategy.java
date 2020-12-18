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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.EndpointsInUpdatingEntry.EndpointAndStep;
import com.linecorp.armeria.common.util.Ticker;

final class WeightRampingUpStrategy implements EndpointSelectionStrategy {

    private static final Ticker defaultTicker = Ticker.systemTicker();

    private final EndpointWeightTransition weightTransition;
    private final ScheduledExecutorService executor;
    private final long rampingUpIntervalMillis;
    private final int totalSteps;
    private final long updatingTaskWindowNanos;
    private final Ticker ticker;

    WeightRampingUpStrategy(EndpointWeightTransition weightTransition,
                            ScheduledExecutorService executor, long rampingUpIntervalMillis,
                            int totalSteps, long updatingTaskWindowMillis) {
        this(weightTransition, executor, rampingUpIntervalMillis, totalSteps, updatingTaskWindowMillis,
             defaultTicker);
    }

    @VisibleForTesting
    WeightRampingUpStrategy(EndpointWeightTransition weightTransition,
                            ScheduledExecutorService executor, long rampingUpIntervalMillis,
                            int totalSteps, long updatingTaskWindowMillis, Ticker ticker) {
        this.weightTransition = requireNonNull(weightTransition, "weightTransition");
        this.executor = requireNonNull(executor, "executor");
        checkArgument(rampingUpIntervalMillis > 0,
                      "rampingUpIntervalMillis: %s (rampingUpIntervalMillis: > 0)", rampingUpIntervalMillis);
        this.rampingUpIntervalMillis = rampingUpIntervalMillis;
        checkArgument(totalSteps > 0, "totalSteps: %s (expected: > 0)", totalSteps);
        this.totalSteps = totalSteps;
        checkArgument(updatingTaskWindowMillis > 0,
                      "updatingTaskWindowMillis: %s (updatingTaskWindowMillis: > 0)", updatingTaskWindowMillis);
        updatingTaskWindowNanos = TimeUnit.MILLISECONDS.toNanos(updatingTaskWindowMillis);
        this.ticker = requireNonNull(ticker, "ticker");
    }

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new RampingUpEndpointWeightSelector(endpointGroup);
    }

    @VisibleForTesting
    final class RampingUpEndpointWeightSelector extends AbstractEndpointSelector {

        private volatile WeightedRandomDistributionEndpointSelector endpointSelector;

        private final List<Endpoint> oldEndpoints = new ArrayList<>();

        @VisibleForTesting
        final Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = new ArrayDeque<>();

        @Nullable
        private List<Endpoint> unhandledNewEndpoints;

        RampingUpEndpointWeightSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            final List<Endpoint> initialEndpoints = endpointGroup.endpoints();
            endpointSelector = new WeightedRandomDistributionEndpointSelector(initialEndpoints);
            oldEndpoints.addAll(initialEndpoints);
            endpointGroup.addListener(this::updateEndpoints);
            if (endpointGroup instanceof DynamicEndpointGroup) {
                ((DynamicEndpointGroup) endpointGroup).whenClosed().thenRunAsync(this::close, executor);
            }
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            return endpointSelector.selectEndpoint();
        }

        @VisibleForTesting
        WeightedRandomDistributionEndpointSelector endpointSelector() {
            return endpointSelector;
        }

        private void updateEndpoints(List<Endpoint> newEndpoints) {
            // Use the executor so the order of endpoints change is guaranteed.
            executor.execute(() -> updateEndpoints0(newEndpoints));
        }

        // Only executed by the executor.
        private void updateEndpoints0(List<Endpoint> newEndpoints) {
            unhandledNewEndpoints = null;
            if (updatingTaskWindowNanos > 0) {
                if (canAddToPrevEntry()) {
                    // Update weight right away and combine updating weight schedule with the previous Entry.
                    final Set<EndpointAndStep> newlyAddedEndpoints = removeOrUpdateEndpoints(newEndpoints);
                    if (!newlyAddedEndpoints.isEmpty()) {
                        updateWeightAndStep(newlyAddedEndpoints);
                        endpointsInUpdatingEntries.getLast().addEndpoints(newlyAddedEndpoints);
                    }
                    // Should recreate endpointSelector even when newlyAddedEndpoints is empty because
                    // the oldEndpoints is changed.
                    buildEndpointSelector();
                    return;
                }

                if (canAddToNextEntry()) {
                    // Combine with the next entry and let it handle this newEndpoints when
                    // updateEndpointWeight() is executed.
                    unhandledNewEndpoints = newEndpoints;
                    return;
                }
            }

            final Set<EndpointAndStep> newlyAddedEndpoints = removeOrUpdateEndpoints(newEndpoints);
            if (newlyAddedEndpoints.isEmpty()) {
                // newlyAddedEndpoints is empty which means that oldEndpoints might be changed.
                // So rebuild the endpoint selector.
                buildEndpointSelector();
                return;
            }

            updateWeightAndStep(newlyAddedEndpoints);

            // Check again because newlyAddedEndpoints can be removed in updateWeightAndStep method.
            if (!newlyAddedEndpoints.isEmpty()) {
                final ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(
                        this::updateWeightAndStep, rampingUpIntervalMillis,
                        rampingUpIntervalMillis, TimeUnit.MILLISECONDS);
                final EndpointsInUpdatingEntry entry = new EndpointsInUpdatingEntry(
                        newlyAddedEndpoints, scheduledFuture, ticker, rampingUpIntervalMillis);
                endpointsInUpdatingEntries.add(entry);
            }
            buildEndpointSelector();
        }

        private void buildEndpointSelector() {
            final ImmutableList.Builder<Endpoint> targetEndpointsBuilder = ImmutableList.builder();
            targetEndpointsBuilder.addAll(oldEndpoints);
            endpointsInUpdatingEntries.forEach(
                    entry -> entry.endpointAndSteps().forEach(
                            endpointAndStep -> targetEndpointsBuilder.add(
                                    endpointAndStep.endpoint().withWeight(endpointAndStep.currentWeight()))));
            endpointSelector = new WeightedRandomDistributionEndpointSelector(targetEndpointsBuilder.build());
        }

        private boolean canAddToPrevEntry() {
            final EndpointsInUpdatingEntry lastEndpointsInUpdatingEntry = endpointsInUpdatingEntries.peekLast();
            return lastEndpointsInUpdatingEntry != null &&
                   ticker.read() - lastEndpointsInUpdatingEntry.lastUpdatedTime <= updatingTaskWindowNanos;
        }

        private boolean canAddToNextEntry() {
            final EndpointsInUpdatingEntry nextEndpointsInUpdatingEntry = endpointsInUpdatingEntries.peek();
            return nextEndpointsInUpdatingEntry != null &&
                   nextEndpointsInUpdatingEntry.nextUpdatingTime - ticker.read() <= updatingTaskWindowNanos;
        }

        /**
         * Removes endpoints in oldEndpoints and endpointsInUpdatingEntries which newEndpoints do not contain.
         * This also returns the the {@link Set} of {@link EndpointAndStep}s whose endpoints are not in
         * in oldEndpoints and endpointsInUpdatingEntries.
         */
        private Set<EndpointAndStep> removeOrUpdateEndpoints(List<Endpoint> newEndpoints) {
            final Map<Endpoint, Endpoint> newEndpointsMap = new HashMap<>(newEndpoints.size());
            // The value is retrieved to compare the weight of the endpoint.
            newEndpoints.forEach(newEndpoint -> newEndpointsMap.put(newEndpoint, newEndpoint));

            final List<Endpoint> replacedOldEndpoints = new ArrayList<>();
            for (final Iterator<Endpoint> i = oldEndpoints.iterator(); i.hasNext();) {
                final Endpoint oldEndpoint = i.next();
                final Endpoint newEndpoint = newEndpointsMap.remove(oldEndpoint);
                if (newEndpoint == null) {
                    // newEndpoints does not have this old endpoint so we remove it.
                    i.remove();
                    continue;
                }

                if (oldEndpoint.weight() > newEndpoint.weight()) {
                    // The weight of the new endpoint is lower than the old endpoint so we just replace it
                    // because we don't have to ramp up the weight.
                    replacedOldEndpoints.add(newEndpoint);
                    i.remove();
                } else if (oldEndpoint.weight() < newEndpoint.weight()) {
                    // The weight of the new endpoint is greater than the old endpoint so we remove the
                    // old one and put the newEndpoint back.
                    newEndpointsMap.put(newEndpoint, newEndpoint);
                    i.remove();
                } else {
                    // The weights are same so we keep the old endpoint.
                }
            }
            if (!replacedOldEndpoints.isEmpty()) {
                oldEndpoints.addAll(replacedOldEndpoints);
            }

            for (final Iterator<EndpointsInUpdatingEntry> i = endpointsInUpdatingEntries.iterator();
                 i.hasNext();) {
                final EndpointsInUpdatingEntry endpointsInUpdatingEntry = i.next();

                final Set<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntry.endpointAndSteps();
                removeOrUpdateEndpoints(endpointAndSteps, newEndpointsMap);
                if (endpointAndSteps.isEmpty()) {
                    // All endpointAndSteps are removed so remove the entry completely.
                    i.remove();
                    endpointsInUpdatingEntry.scheduledFuture.cancel(true);
                }
            }

            // At this point, newEndpointsMap only contains the new endpoints that have to be ramped up.
            if (newEndpointsMap.isEmpty()) {
                return ImmutableSet.of();
            }
            final Set<EndpointAndStep> newlyAddedEndpoints = new HashSet<>(newEndpointsMap.size());
            newEndpointsMap.keySet().forEach(
                    endpoint -> newlyAddedEndpoints.add(new EndpointAndStep(endpoint)));
            return newlyAddedEndpoints;
        }

        private void removeOrUpdateEndpoints(Set<EndpointAndStep> endpointAndSteps,
                                             Map<Endpoint, Endpoint> newEndpointsMap) {
            final List<EndpointAndStep> replacedEndpoints = new ArrayList<>();
            for (final Iterator<EndpointAndStep> i = endpointAndSteps.iterator(); i.hasNext();) {
                final EndpointAndStep endpointAndStep = i.next();
                final Endpoint endpointInUpdating = endpointAndStep.endpoint();
                final Endpoint newEndpoint = newEndpointsMap.remove(endpointInUpdating);
                if (newEndpoint == null) {
                    // newEndpointsMap does not contain endpointInUpdating so just remove the endpoint.
                    i.remove();
                    continue;
                }

                if (endpointInUpdating.weight() == newEndpoint.weight()) {
                    // Same weight so don't to anything. Ramping up happens as it is scheduled.
                } else if (endpointAndStep.currentWeight() > newEndpoint.weight()) {
                    // Don't need to update the weight anymore so we add the newEndpoint to oldEndpoints and
                    // remove from the iterator.
                    oldEndpoints.add(newEndpoint);
                    i.remove();
                } else {
                    // Should replace the existing endpoint with the new one.
                    // To replace, just remove and add the newEndpoint later after iteration is over.
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
                        removeOrUpdateEndpoints(unhandledNewEndpoints);
                final EndpointsInUpdatingEntry entry = endpointsInUpdatingEntries.peek();
                assert entry != null;
                entry.addEndpoints(newlyAddedEndpoints);
                unhandledNewEndpoints = null;
            }
            final EndpointsInUpdatingEntry entry = endpointsInUpdatingEntries.poll();
            assert entry != null;

            final Set<EndpointAndStep> endpointAndSteps = entry.endpointAndSteps();
            updateWeightAndStep(endpointAndSteps);
            if (endpointAndSteps.isEmpty()) {
                entry.scheduledFuture.cancel(true);
            } else {
                // Added to the last of the entries.
                endpointsInUpdatingEntries.add(entry);
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
                    oldEndpoints.add(endpoint);
                    i.remove();
                } else {
                    final int calculated =
                            weightTransition.compute(endpoint, step, totalSteps);
                    final int currentWeight = Math.max(Math.min(calculated, endpoint.weight()), 0);
                    endpointAndStep.currentWeight(currentWeight);
                }
            }
        }

        private void close() {
            EndpointsInUpdatingEntry entry;
            while ((entry = endpointsInUpdatingEntries.poll()) != null) {
                entry.scheduledFuture.cancel(true);
            }
        }
    }

    @VisibleForTesting
    static final class EndpointsInUpdatingEntry {

        private final Set<EndpointAndStep> endpointAndSteps;
        private final Ticker ticker;
        private final long rampingUpIntervalNanos;

        final ScheduledFuture<?> scheduledFuture;
        long lastUpdatedTime;
        long nextUpdatingTime;

        EndpointsInUpdatingEntry(Set<EndpointAndStep> endpointAndSteps, ScheduledFuture<?> scheduledFuture,
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
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }

                final EndpointAndStep that = (EndpointAndStep) o;
                // Use weight also.
                return endpoint.equals(that.endpoint) &&
                       endpoint.weight() == that.endpoint().weight() &&
                       step == that.step &&
                       currentWeight == that.currentWeight();
            }

            @Override
            public int hashCode() {
                // Do not use step and currentWeight because they are changed during iteration.
                return endpoint.hashCode() * 31 + endpoint.weight();
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
