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

import static com.linecorp.armeria.client.endpoint.EndpointWeightTransition.linear;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.EndpointsRampingUpEntry.EndpointAndStep;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.RampingUpEndpointWeightSelector;
import com.linecorp.armeria.client.endpoint.WeightedRandomDistributionEndpointSelector.Entry;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;

final class WeightRampingUpStrategyTest {

    private static final AtomicLong ticker = new AtomicLong();

    private static final Queue<Runnable> scheduledJobs = new ConcurrentLinkedQueue<>();
    private static final Queue<ScheduledFuture<?>> scheduledFutures = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        ticker.set(0);
        scheduledJobs.clear();
        scheduledFutures.clear();
    }

    @Test
    void endpointIsRemovedIfNotInNewEndpoints() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 2);
        ticker.addAndGet(1);
        // Because we set only foo1.com, foo.com is removed.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo1.com")));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactly(
                                              Endpoint.of("foo1.com")
                                      );
    }

    @Test
    void rampingUpIsDoneAfterNumberOfSteps() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 2);
        ticker.addAndGet(1);
        endpointGroup.addEndpoint(Endpoint.of("bar.com"));
        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE).containsExactly(
                endpointAndStep(Endpoint.of("bar.com"), 1, 500));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(500)
                                      );

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(20));
        scheduledJobs.poll().run();
        // Ramping up is done because the step reached the numberOfSteps.

        assertThat(selector.endpointsRampingUp).isEmpty();
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com")
                                      );
    }

    @Test
    void endpointsAreAddedToPreviousEntry_IfTheyAreAddedWithinWindow() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);

        ticker.addAndGet(1);

        addSecondEndpoints(endpointGroup, selector);

        ticker.addAndGet(1);

        endpointGroup.addEndpoint(Endpoint.of("baz.com"));
        endpointGroup.addEndpoint(Endpoint.of("baz1.com"));

        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps1 = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps1).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("bar.com"), 1, 100),
                                             endpointAndStep(Endpoint.of("bar1.com"), 1, 100),
                                             endpointAndStep(Endpoint.of("baz.com"), 1, 100),
                                             endpointAndStep(Endpoint.of("baz1.com"), 1, 100));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100),
                                              Endpoint.of("baz.com").withWeight(100),
                                              Endpoint.of("baz1.com").withWeight(100)
                                      );
    }

    @Test
    void endpointsAreAddedToNextEntry_IfTheyAreAddedWithinWindow() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);
        ticker.addAndGet(1);

        addSecondEndpoints(endpointGroup, selector);

        // Add 19 seconds so now it's within the window of second ramping up of bar.com and bar1.com.
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(19));

        // baz endpoint is not calculated and removed because it's overridden by the next setEndpoints() call.
        endpointGroup.addEndpoint(Endpoint.of("baz.com"));
        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps1 = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps1).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("bar.com"), 1, 100),
                                             endpointAndStep(Endpoint.of("bar1.com"), 1, 100));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );

        // The weights of qux.com and qux1.com will be ramped up with bar.com and bar1.com.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com"), Endpoint.of("bar1.com"),
                                                    Endpoint.of("qux.com"), Endpoint.of("qux1.com")));

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        scheduledJobs.poll().run();
        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps2 = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps2).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("bar.com"), 2, 200),
                                             endpointAndStep(Endpoint.of("bar1.com"), 2, 200),
                                             endpointAndStep(Endpoint.of("qux.com"), 1, 100),
                                             endpointAndStep(Endpoint.of("qux1.com"), 1, 100));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              // 1000 * (2 / 10) => weight * (step / numberOfSteps)
                                              Endpoint.of("bar.com").withWeight(200),
                                              Endpoint.of("bar1.com").withWeight(200),
                                              // 1000 * (1 / 10) => weight * (step / numberOfSteps)
                                              Endpoint.of("qux.com").withWeight(100),
                                              Endpoint.of("qux1.com").withWeight(100)
                                      );
    }

    @Test
    void setEndpointWithDifferentWeight() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);
        ticker.addAndGet(1);

        // Set an endpoint with the weight which is lower than current weight so ramping up is
        // not happening for the endpoint.
        endpointGroup.setEndpoints(
                ImmutableList.of(Endpoint.of("foo.com").withWeight(100), Endpoint.of("foo1.com")));
        assertThat(selector.endpointsRampingUp).hasSize(0);
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(100), Endpoint.of("foo1.com")
                                      );

        // Set an endpoint with the weight which is greater than current weight so ramping up is scheduled.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com").withWeight(3000),
                                                    Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com")));

        assertThat(selector.endpointsRampingUp).hasSize(1);
        Set<EndpointAndStep> endpointAndSteps = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("foo.com").withWeight(3000), 1, 300),
                                            endpointAndStep(Endpoint.of("bar.com"), 1, 100));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(300), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100)
                                      );

        // Execute the scheduled job so the weight is ramped up.
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(20));
        scheduledJobs.poll().run();

        assertThat(selector.endpointsRampingUp).hasSize(1);
        endpointAndSteps = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("foo.com").withWeight(3000), 2, 600),
                                            endpointAndStep(Endpoint.of("bar.com"), 2, 200));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              // 3000 * 2 / 10 = 600
                                              Endpoint.of("foo.com").withWeight(600), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(200)
                                      );

        ticker.addAndGet(1);

        // Set an endpoint with the weight which is lower than current weight so scheduling is canceled.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com").withWeight(599),
                                                    Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com")));
        assertThat(selector.endpointsRampingUp).hasSize(1);
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE).containsExactly(
                endpointAndStep(Endpoint.of("bar.com"), 2, 200));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(599), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(200)
                                      );
    }

    @Test
    void rampingUpEndpointsAreRemoved() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);

        ticker.addAndGet(1);

        addSecondEndpoints(endpointGroup, selector);

        ticker.addAndGet(1);

        // bar1.com is removed and the weight of bar.com is ramped up.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com").withWeight(3000)));

        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE).containsExactly(
                endpointAndStep(Endpoint.of("bar.com").withWeight(3000), 1, 300));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(300)
                                      );

        ticker.addAndGet(1);
        // bar.com is removed.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com")));
        assertThat(selector.endpointsRampingUp).isEmpty();
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com")
                                      );
        assertThat(scheduledFutures).hasSize(1);
        verify(scheduledFutures.poll(), times(1)).cancel(true);
    }

    @Test
    void sameEndpointsAreSummed() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);

        ticker.addAndGet(1);

        addSecondEndpoints(endpointGroup, selector);

        ticker.addAndGet(1);

        // The three bar.com are converted into onw bar.com with 3000 weight.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com"), Endpoint.of("bar.com"),
                                                    Endpoint.of("bar.com"), Endpoint.of("bar1.com")));

        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("bar.com").withWeight(3000), 1, 300),
                                            endpointAndStep(Endpoint.of("bar1.com"), 1, 100)
                                    );
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(300),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );
    }

    @Test
    void scheduledIsCanceledWhenEndpointGroupIsClosed() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);

        ticker.addAndGet(1);

        addSecondEndpoints(endpointGroup, selector);
        assertThat(scheduledFutures).hasSize(1);

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(10));

        endpointGroup.addEndpoint(Endpoint.of("baz.com"));
        endpointGroup.addEndpoint(Endpoint.of("baz1.com"));
        assertThat(scheduledFutures).hasSize(2);

        endpointGroup.close();

        ScheduledFuture<?> scheduledFuture;
        while ((scheduledFuture = scheduledFutures.poll()) != null) {
            verify(scheduledFuture, times(1)).cancel(true);
        }
    }

    private static RampingUpEndpointWeightSelector setInitialEndpoints(DynamicEndpointGroup endpointGroup,
                                                                       int numberOfSteps) {
        final WeightRampingUpStrategy strategy =
                new WeightRampingUpStrategy(linear(), ImmediateExecutor::new,
                                            20000, numberOfSteps, 1000, ticker::get);

        final List<Endpoint> endpoints = ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"));
        endpointGroup.setEndpoints(endpoints);
        final RampingUpEndpointWeightSelector selector =
                (RampingUpEndpointWeightSelector) strategy.newSelector(endpointGroup);

        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com")
                                      );
        return selector;
    }

    private static List<Endpoint> endpointsFromSelectorEntry(RampingUpEndpointWeightSelector selector) {
        final ImmutableList.Builder<Endpoint> builder = new ImmutableList.Builder<>();
        final List<Entry> entries = selector.endpointSelector().entries();
        entries.forEach(entry -> builder.add(entry.endpoint()));
        return builder.build();
    }

    private void addSecondEndpoints(DynamicEndpointGroup endpointGroup,
                                    RampingUpEndpointWeightSelector selector) {
        endpointGroup.addEndpoint(Endpoint.of("bar.com"));
        endpointGroup.addEndpoint(Endpoint.of("bar1.com"));

        assertThat(selector.endpointsRampingUp).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = selector.endpointsRampingUp.peek().endpointAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("bar.com"), 1, 100),
                                            endpointAndStep(Endpoint.of("bar1.com"), 1, 100));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              // 1000 * (1 / 10) => weight * (step / numberOfSteps)
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );
    }

    private static EndpointAndStep endpointAndStep(Endpoint endpoint, int step, int currentWeight) {
        final EndpointAndStep endpointAndStep = new EndpointAndStep(endpoint, step);
        endpointAndStep.currentWeight(currentWeight);
        return endpointAndStep;
    }

    /**
     * A Comparator which includes the weight of an endpoint to compare.
     */
    enum EndpointComparator implements Comparator<Endpoint> {

        INSTANCE;

        @Override
        public int compare(Endpoint o1, Endpoint o2) {
            if (o1.equals(o2) && o1.weight() == o2.weight()) {
                return 0;
            }
            return -1;
        }
    }

    /**
     * A Comparator which includes the weight of an endpoint to compare.
     */
    private enum EndpointAndStepComparator implements Comparator<EndpointAndStep> {

        INSTANCE;

        @Override
        public int compare(EndpointAndStep o1, EndpointAndStep o2) {
            final Endpoint endpoint1 = o1.endpoint();
            final Endpoint endpoint2 = o2.endpoint();
            if (endpoint1.equals(endpoint2) &&
                endpoint1.weight() == endpoint2.weight() &&
                o1.step() == o2.step() &&
                o1.currentWeight() == o2.currentWeight()) {
                return 0;
            }
            return -1;
        }
    }

    private static class ImmediateExecutor extends DefaultEventLoop {

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            scheduledJobs.add(command);
            final ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
            scheduledFutures.add(scheduledFuture);
            return scheduledFuture;
        }
    }
}
