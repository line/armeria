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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.RampingUpWeightedRoundRobinStrategy.EndpointsInUpdatingEntry.EndpointAndStep;
import com.linecorp.armeria.common.util.Ticker;

final class RampingUpWeightedRoundRobinStrategy implements EndpointSelectionStrategy {

    private final EndpointWeightTransition weightTransition;
    private final ScheduledExecutorService executor;
    private final long rampingUpIntervalMillis;
    private final int totalSteps;
    private final long updatingTaskWindowNanos;
    private final Ticker ticker;

    RampingUpWeightedRoundRobinStrategy(EndpointWeightTransition weightTransition,
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

        private volatile WeightBasedRandomEndpointSelector endpointSelector;

        @Nullable
        private List<Endpoint> oldEndpoints = new ArrayList<>();

        @VisibleForTesting
        final Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = new ArrayDeque<>();

        @Nullable
        private List<Endpoint> unhandledNewEndpoints;

        RampingUpEndpointWeightSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            final List<Endpoint> initialEndpoints = endpointGroup.endpoints();
            endpointSelector = new WeightBasedRandomEndpointSelector(initialEndpoints);
            oldEndpoints.addAll(initialEndpoints);
            endpointGroup.addListener(this::updateEndpoints);
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            return endpointSelector.selectEndpoint();
        }

        @VisibleForTesting
        WeightBasedRandomEndpointSelector endpointSelector() {
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
                if (addToPrevEntry()) {
                    // Update weight right away and combine updating weight schedule with the previous Entry.
                    final Deque<EndpointAndStep> newlyAddedEndpoints = updateEndpoints1(newEndpoints);
                    final ImmutableList.Builder<Endpoint> builder = ImmutableList.builder();
                    addCurrentEndpoints(builder);

                    if (!newlyAddedEndpoints.isEmpty()) {
                        updateEndpointWeight(newlyAddedEndpoints, builder);
                        endpointsInUpdatingEntries.getLast().addEndpoints(newlyAddedEndpoints);
                    }
                    // Should recreate endpointSelector because the oldEndpoints is changed
                    // when newlyAddedEndpoints is empty.
                    endpointSelector = new WeightBasedRandomEndpointSelector(builder.build());
                    return;
                }

                if (addToNextEntry()) {
                    // Combine with the Entry and let it handle this newEndpoints when it calls updateWeight().
                    unhandledNewEndpoints = newEndpoints;
                    return;
                }
            }

            final Deque<EndpointAndStep> newlyAddedEndpoints = updateEndpoints1(newEndpoints);
            if (newlyAddedEndpoints.isEmpty()) {
                final ImmutableList.Builder<Endpoint> builder = ImmutableList.builder();
                addCurrentEndpoints(builder);
                // Should recreate endpointSelector because the oldEndpoints is changed
                // when newlyAddedEndpoints is empty.
                endpointSelector = new WeightBasedRandomEndpointSelector(builder.build());
                return;
            }

            final EndpointsInUpdatingEntry endpointsInUpdatingEntry =
                    new EndpointsInUpdatingEntry(newlyAddedEndpoints, ticker,
                                                 TimeUnit.MILLISECONDS.toNanos(rampingUpIntervalMillis));
            endpointsInUpdatingEntries.add(endpointsInUpdatingEntry);
            updateEndpointWeight();
        }

        private boolean addToPrevEntry() {
            final EndpointsInUpdatingEntry lastEndpointsInUpdatingEntry = endpointsInUpdatingEntries.peekLast();
            return lastEndpointsInUpdatingEntry != null &&
                   ticker.read() - lastEndpointsInUpdatingEntry.lastUpdatedTime <= updatingTaskWindowNanos;
        }

        private boolean addToNextEntry() {
            final EndpointsInUpdatingEntry nextEndpointsInUpdatingEntry = endpointsInUpdatingEntries.peek();
            return nextEndpointsInUpdatingEntry != null &&
                   nextEndpointsInUpdatingEntry.nextUpdatingTime - ticker.read() <= updatingTaskWindowNanos;
        }

        private void addCurrentEndpoints(Builder<Endpoint> builder) {
            assert oldEndpoints != null;
            builder.addAll(oldEndpoints);
            endpointsInUpdatingEntries.forEach(entry -> {
                entry.endpointAndSteps().forEach(endpointAndStep -> {
                    builder.add(endpointAndStep.endpoint().withWeight(endpointAndStep.currentWeight()));
                });
            });
        }

        private Deque<EndpointAndStep> updateEndpoints1(List<Endpoint> newEndpoints) {
            final List<Endpoint> replacedOldEndpoints = new ArrayList<>();
            assert oldEndpoints != null;
            for (final Iterator<Endpoint> i = oldEndpoints.iterator(); i.hasNext();) {
                final Endpoint oldEndpoint = i.next();
                for (Endpoint newEndpoint : newEndpoints) {
                    if (oldEndpoint.equals(newEndpoint)) {
                        if (oldEndpoint.weight() > newEndpoint.weight()) {
                            i.remove();
                            // The weight of the new endpoint is lower than old one so we just replace it.
                            replacedOldEndpoints.add(newEndpoint);
                        } else if (oldEndpoint.weight() < newEndpoint.weight()) {
                            // The weight of the new endpoint is greater than old one so we just remove the
                            // old endpoint. The new endpoint will be added to
                            // newlyAddedEndpoints at the bottom of this method.
                            i.remove();
                        }
                        break;
                    }
                }
            }
            if (!replacedOldEndpoints.isEmpty()) {
                oldEndpoints.addAll(replacedOldEndpoints);
            }

            for (final Iterator<EndpointsInUpdatingEntry> i = endpointsInUpdatingEntries.iterator();
                 i.hasNext();) {
                final EndpointsInUpdatingEntry endpointsInUpdatingEntry = i.next();

                final Deque<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntry.endpointAndSteps();
                removeIfNotInNewEndpoints(endpointAndSteps, newEndpoints);
                if (endpointAndSteps.isEmpty()) {
                    // All endpointAndSteps are removed so remove the entry from the Deque.
                    i.remove();
                    final ScheduledFuture<?> scheduledFuture = endpointsInUpdatingEntry.scheduledFuture;
                    if (scheduledFuture != null) {
                        scheduledFuture.cancel(true);
                    }
                }
            }

            final Deque<EndpointAndStep> newlyAddedEndpoints = new ArrayDeque<>();
            for (Endpoint newEndpoint : newEndpoints) {
                // We don't have to compare the weight because the old endpoint whose host is same and weight
                // is different is already removed.
                if (oldEndpoints.contains(newEndpoint)) {
                    continue;
                }

                // Two for loops are used but it's only the number of endpoints in updating because there are
                // no duplicate endpoints. So no big deal.
                boolean found = false;
                for (EndpointsInUpdatingEntry endpointsInUpdatingEntry : endpointsInUpdatingEntries) {
                    for (EndpointAndStep endpointAndStep : endpointsInUpdatingEntry.endpointAndSteps()) {
                        if (endpointAndStep.endpoint().equals(newEndpoint)) {
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }

                if (!found) {
                    newlyAddedEndpoints.add(new EndpointAndStep(newEndpoint));
                }
            }
            return newlyAddedEndpoints;
        }

        private void removeIfNotInNewEndpoints(Deque<EndpointAndStep> endpointAndSteps,
                                               List<Endpoint> newEndpoints) {
            final List<EndpointAndStep> replacedEndpoints = new ArrayList<>();
            for (final Iterator<EndpointAndStep> i = endpointAndSteps.iterator();
                 i.hasNext();) {
                removeIfNotInNewEndpoints(i, newEndpoints, replacedEndpoints);
            }

            if (!replacedEndpoints.isEmpty()) {
                endpointAndSteps.addAll(replacedEndpoints);
            }
        }

        private void removeIfNotInNewEndpoints(Iterator<EndpointAndStep> i, List<Endpoint> newEndpoints,
                                               List<EndpointAndStep> replacedEndpoints) {
            final EndpointAndStep endpointAndStep = i.next();
            final Endpoint endpointInUpdating = endpointAndStep.endpoint();
            for (Endpoint newEndpoint : newEndpoints) {
                if (endpointInUpdating.equals(newEndpoint)) {
                    if (endpointInUpdating.weight() == newEndpoint.weight()) {
                        // Same weight so we don't do anything.
                    } else if (endpointAndStep.currentWeight() > newEndpoint.weight()) {
                        // Don't need to update the weight anymore so we add the newEndpoint to oldEndpoints.
                        assert oldEndpoints != null;
                        oldEndpoints.add(newEndpoint);
                        i.remove();
                    } else {
                        // Should replace the existing endpoint with the new one.
                        // To replace, just remove and add the newEndpoint later after iteration is over.
                        replacedEndpoints.add(new EndpointAndStep(newEndpoint, endpointAndStep.step));
                        i.remove();
                    }
                    return;
                }
            }
            // The endpoint is not in newEndpoints so remove it.
            i.remove();
        }

        private void updateEndpointWeight() {
            final EndpointsInUpdatingEntry entry = endpointsInUpdatingEntries.peekFirst();
            assert entry != null;
            if (unhandledNewEndpoints != null) {
                final Deque<EndpointAndStep> newlyAddedEndpoints = updateEndpoints1(unhandledNewEndpoints);
                entry.addEndpoints(newlyAddedEndpoints);
                unhandledNewEndpoints = null;
            }

            final ImmutableList.Builder<Endpoint> builder = ImmutableList.builder();
            final Deque<EndpointAndStep> endpointAndSteps = entry.endpointAndSteps();
            updateEndpointWeight(endpointAndSteps, builder);
            assert oldEndpoints != null;
            builder.addAll(oldEndpoints);

            endpointSelector = new WeightBasedRandomEndpointSelector(builder.build());
            endpointsInUpdatingEntries.pollFirst();
            if (!endpointAndSteps.isEmpty()) {
                final ScheduledFuture<?> scheduledFuture = executor.schedule(
                        (Runnable) this::updateEndpointWeight,
                        rampingUpIntervalMillis,
                        TimeUnit.MILLISECONDS);
                entry.scheduledFuture(scheduledFuture);
                // Added to the last of the entries.
                endpointsInUpdatingEntries.add(entry);
            }
        }

        private void updateEndpointWeight(Deque<EndpointAndStep> endpointAndSteps,
                                          Builder<Endpoint> builder) {
            assert oldEndpoints != null;
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
                    final int currentWeight = Math.max(Math.min(calculated, endpoint.weight()), 1);
                    endpointAndStep.currentWeight(currentWeight);
                    builder.add(endpoint.withWeight(currentWeight));
                }
            }
        }
    }

    @VisibleForTesting
    static class EndpointsInUpdatingEntry {

        private final Deque<EndpointAndStep> endpointAndSteps;
        private final Ticker ticker;
        private final long rampingUpIntervalNanos;

        @Nullable
        ScheduledFuture<?> scheduledFuture;
        long lastUpdatedTime;
        long nextUpdatingTime;

        EndpointsInUpdatingEntry(Deque<EndpointAndStep> endpointAndSteps, Ticker ticker,
                                 long rampingUpIntervalNanos) {
            this.endpointAndSteps = endpointAndSteps;
            this.ticker = ticker;
            this.rampingUpIntervalNanos = rampingUpIntervalNanos;
        }

        Deque<EndpointAndStep> endpointAndSteps() {
            return endpointAndSteps;
        }

        void addEndpoints(Deque<EndpointAndStep> endpoints) {
            endpointAndSteps.addAll(endpoints);
        }

        void scheduledFuture(ScheduledFuture<?> scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
            lastUpdatedTime = ticker.read();
            nextUpdatingTime = lastUpdatedTime + rampingUpIntervalNanos;
        }

        @VisibleForTesting
        static class EndpointAndStep {

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

            Endpoint endpoint() {
                return endpoint;
            }

            // Added for testing.
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

            // Added for testing.
            @Override
            public int hashCode() {
                return ((endpoint.hashCode() * 31 + endpoint.weight()) * 31 + step) * 31 + currentWeight;
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
