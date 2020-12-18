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

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.EndpointsInUpdatingEntry;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.EndpointsInUpdatingEntry.EndpointAndStep;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategy.RampingUpEndpointWeightSelector;
import com.linecorp.armeria.client.endpoint.WeightedRandomDistributionEndpointSelector.Entry;

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
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactly(
                                              Endpoint.of("foo1.com")
                                      );
    }

    @Test
    void updatingWeightIsDoneAfterNumberOfSteps() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 2);
        ticker.addAndGet(1);
        endpointGroup.addEndpoint(Endpoint.of("bar.com"));
        Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps).containsExactly(
                endpointAndStep(Endpoint.of("bar.com"), 1, 500));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(500)
                                      );

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(20));
        scheduledJobs.poll().run();
        // Updating weight is done because the step reached the numberOfSteps.

        endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).isEmpty();
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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

        final Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps1 = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps1).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("bar.com"), 1, 100),
                endpointAndStep(Endpoint.of("bar1.com"), 1, 100),
                endpointAndStep(Endpoint.of("baz.com"), 1, 100),
                endpointAndStep(Endpoint.of("baz1.com"), 1, 100));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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

        // Add 19 seconds so now it's within the window of second updating weight of bar.com and bar1.com.
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(19));

        // baz endpoint is not calculated and removed because it's overridden by the next setEndpoints() call.
        endpointGroup.addEndpoint(Endpoint.of("baz.com"));
        Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps1 = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps1).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("bar.com"), 1, 100),
                endpointAndStep(Endpoint.of("bar1.com"), 1, 100));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );

        // The weights of qux.com and qux1.com will be updated with bar.com and bar1.com.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com"), Endpoint.of("bar1.com"),
                                                    Endpoint.of("qux.com"), Endpoint.of("qux1.com")));

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        scheduledJobs.poll().run();
        endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps2 = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps2).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("bar.com"), 2, 200),
                endpointAndStep(Endpoint.of("bar1.com"), 2, 200),
                endpointAndStep(Endpoint.of("qux.com"), 1, 100),
                endpointAndStep(Endpoint.of("qux1.com"), 1, 100));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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

        // Set an endpoint with the weight which is lower than current weight so updating weight is
        // not happening for the endpoint.
        endpointGroup.setEndpoints(
                ImmutableList.of(Endpoint.of("foo.com").withWeight(100), Endpoint.of("foo1.com")));
        Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(0);
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(100), Endpoint.of("foo1.com")
                                      );

        // Set an endpoint with the weight which is greater than current weight so updating weight is scheduled.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com").withWeight(3000),
                                                    Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com")));

        endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        Set<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("foo.com").withWeight(3000), 1, 300),
                endpointAndStep(Endpoint.of("bar.com"), 1, 100));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(300), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100)
                                      );

        // Execute the scheduled job so the weight is updated.
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(20));
        scheduledJobs.poll().run();

        endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        endpointAndSteps = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("foo.com").withWeight(3000), 2, 600),
                endpointAndStep(Endpoint.of("bar.com"), 2, 200));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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
        endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        assertThat(endpointAndSteps).containsExactly(
                endpointAndStep(Endpoint.of("bar.com"), 2, 200));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(599), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(200)
                                      );
    }

    @Test
    void endpointsInUpdatingAreRemoved() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RampingUpEndpointWeightSelector selector = setInitialEndpoints(endpointGroup, 10);

        ticker.addAndGet(1);

        addSecondEndpoints(endpointGroup, selector);

        ticker.addAndGet(1);

        // bar1.com is removed and the weight of bar.com is updated.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                    Endpoint.of("bar.com").withWeight(3000)));

        final Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps).containsExactly(
                endpointAndStep(Endpoint.of("bar.com").withWeight(3000), 1, 300));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(300)
                                      );

        ticker.addAndGet(1);
        // bar.com is removed.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com")));
        assertThat(endpointsInUpdatingEntries).isEmpty();
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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

        final Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("bar.com").withWeight(3000), 1, 300),
                endpointAndStep(Endpoint.of("bar1.com"), 1, 100)
                );
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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
                new WeightRampingUpStrategy(linear(), new ImmediateExecutor(),
                                            20000, numberOfSteps, 1000, ticker::get);

        final List<Endpoint> endpoints = ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"));
        endpointGroup.setEndpoints(endpoints);
        final RampingUpEndpointWeightSelector selector =
                (RampingUpEndpointWeightSelector) strategy.newSelector(endpointGroup);

        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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

        final Deque<EndpointsInUpdatingEntry> endpointsInUpdatingEntries = selector.endpointsInUpdatingEntries;
        assertThat(endpointsInUpdatingEntries).hasSize(1);
        final Set<EndpointAndStep> endpointAndSteps = endpointsInUpdatingEntries.peek().endpointAndSteps();
        assertThat(endpointAndSteps).containsExactlyInAnyOrder(
                endpointAndStep(Endpoint.of("bar.com"), 1, 100),
                endpointAndStep(Endpoint.of("bar1.com"), 1, 100));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(new EndpointComparator())
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
    static class EndpointComparator implements Comparator<Endpoint>, Serializable {
        private static final long serialVersionUID = -3534596922171048613L;

        @Override
        public int compare(Endpoint o1, Endpoint o2) {
            if (o1.host().equals(o2.host()) &&
                Objects.equals(o1.ipAddr(), o2.ipAddr()) &&
                o1.weight() == o2.weight()) {
                if (o1.hasPort() || o2.hasPort() && o1.port() == o2.port()) {
                    return 0;
                }
                return 0;
            }
            return -1;
        }
    }

    private static class ImmediateExecutor implements ScheduledExecutorService {

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            scheduledJobs.add(command);
            final ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
            scheduledFutures.add(scheduledFuture);
            return scheduledFuture;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isTerminated() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
                                             TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }
    }
}
