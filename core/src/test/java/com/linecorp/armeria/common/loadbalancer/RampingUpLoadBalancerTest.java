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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.loadbalancer.RampingUpLoadBalancer.CandidateAndStep;
import com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys;
import com.linecorp.armeria.internal.common.loadbalancer.WeightedObject;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RampingUpLoadBalancerTest {

    private static final AtomicLong ticker = new AtomicLong();

    private static final Queue<Runnable> scheduledJobs = new ConcurrentLinkedQueue<>();
    private static final Queue<Long> initialDelayNanos = new ConcurrentLinkedQueue<>();
    private static final Queue<Long> periodNanos = new ConcurrentLinkedQueue<>();
    private static final Queue<ScheduledFuture<?>> scheduledFutures = new ConcurrentLinkedQueue<>();
    private static final long rampingUpIntervalNanos = TimeUnit.MILLISECONDS.toNanos(20000);
    private static final long rampingUpTaskWindowNanos = TimeUnit.MILLISECONDS.toNanos(1000);
    private static final WeightTransition<Endpoint> weightTransition = WeightTransition.linear();
    private static final List<Endpoint> initialEndpoints = ImmutableList.of(Endpoint.of("foo.com"),
                                                                            Endpoint.of("foo1.com"));
    private static final List<Endpoint> secondEndpoints = ImmutableList.of(Endpoint.of("bar.com"),
                                                                           Endpoint.of("bar1.com"));
    private static final List<Endpoint> thirdEndpoints = ImmutableList.of(Endpoint.of("baz.com"),
                                                                          Endpoint.of("baz1.com"));

    @BeforeEach
    void setUp() {
        ticker.set(0);
        initialDelayNanos.clear();
        periodNanos.clear();
        scheduledJobs.clear();
        scheduledFutures.clear();
    }

    @Test
    void endpointIsRemovedIfNotInNewEndpoints() {
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(2);
        ticker.addAndGet(rampingUpIntervalNanos);
        // Because we set only foo1.com, foo.com is removed.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo1.com")));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactly(
                                              Endpoint.of("foo1.com")
                                      );
    }

    @Test
    void rampingUpIsDoneAfterNumberOfSteps() {
        final int steps = 2;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);
        ticker.addAndGet(rampingUpIntervalNanos);
        final long windowIndex = selector.windowIndex(ticker.get());
        selector.updateCandidates(ImmutableList.<Endpoint>builder()
                                               .addAll(initialEndpoints)
                                               .add(Endpoint.of("bar.com"))
                                               .build());
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps =
                selector.rampingUpWindowsMap.get(windowIndex).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE).containsExactly(
                endpointAndStep(Endpoint.of("bar.com"), 1, steps));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(500)
                                      );

        ticker.addAndGet(rampingUpIntervalNanos);
        scheduledJobs.poll().run();
        // Ramping up is done because the step reached the numberOfSteps.

        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com")
                                      );
    }

    @Test
    void endpointsAreAddedToPreviousEntry_IfTheyAreAddedWithinWindow() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        addSecondEndpoints(selector, steps);

        ticker.addAndGet(rampingUpTaskWindowNanos - 1);

        final long windowIndex = selector.windowIndex(ticker.get());
        selector.updateCandidates(ImmutableList.<Endpoint>builder()
                                               .addAll(initialEndpoints)
                                               .addAll(secondEndpoints)
                                               .addAll(thirdEndpoints)
                                               .build());

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(windowIndex);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps1 =
                selector.rampingUpWindowsMap.get(windowIndex).candidateAndSteps();
        assertThat(endpointAndSteps1).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("bar.com"), 1, steps),
                                             endpointAndStep(Endpoint.of("bar1.com"), 1, steps),
                                             endpointAndStep(Endpoint.of("baz.com"), 1, steps),
                                             endpointAndStep(Endpoint.of("baz1.com"), 1, steps));
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
    void endpointsAreAddedToNewEntry_IfAllTheEntryAreRemoved() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        addSecondEndpoints(selector, steps);

        ticker.addAndGet(steps * rampingUpIntervalNanos);

        final long window = selector.windowIndex(ticker.get());
        selector.updateCandidates(ImmutableList.of(Endpoint.of("baz.com"), Endpoint.of("baz1.com")));

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps1 =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps1).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("baz.com"), 1, steps),
                                             endpointAndStep(Endpoint.of("baz1.com"), 1, steps));
    }

    @Test
    void endpointsAreAddedToNextEntry_IfTheyAreAddedWithinWindow() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        long window = selector.windowIndex(ticker.get());
        addSecondEndpoints(selector, steps);

        // Add 19 seconds so now it's within the window of second ramping up of bar.com and bar1.com.
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(19));

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps1 =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps1).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("bar.com"), 1, steps),
                                             endpointAndStep(Endpoint.of("bar1.com"), 1, steps));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        window = selector.windowIndex(ticker.get());

        // The weights of qux.com and qux1.com will be ramped up with bar.com and bar1.com.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                   Endpoint.of("bar.com"), Endpoint.of("bar1.com"),
                                                   Endpoint.of("qux.com"), Endpoint.of("qux1.com")));

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps2 =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps2).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                     .containsExactlyInAnyOrder(
                                             endpointAndStep(Endpoint.of("bar.com"), 2, steps),
                                             endpointAndStep(Endpoint.of("bar1.com"), 2, steps),
                                             endpointAndStep(Endpoint.of("qux.com"), 1, steps),
                                             endpointAndStep(Endpoint.of("qux1.com"), 1, steps));
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
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        // Set an endpoint with the weight which is lower than current weight so ramping up is
        // not happening for the endpoint.
        selector.updateCandidates(
                ImmutableList.of(Endpoint.of("foo.com").withWeight(100), Endpoint.of("foo1.com")));
        assertThat(selector.rampingUpWindowsMap).hasSize(0);
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(100), Endpoint.of("foo1.com")
                                      );

        long window = selector.windowIndex(ticker.get());
        // Set an endpoint with the weight which is greater than the current weight
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com").withWeight(3000),
                                                   Endpoint.of("foo1.com"),
                                                   Endpoint.of("bar.com")));

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        Set<CandidateAndStep<Endpoint>> endpointAndSteps =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("bar.com"), 1, steps));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(3000), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100)
                                      );

        // Execute a scheduled job
        ticker.addAndGet(rampingUpIntervalNanos);
        scheduledJobs.poll().run();
        window = selector.windowIndex(ticker.get());

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        endpointAndSteps = selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("bar.com"), 2, steps));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry)
                .usingElementComparator(EndpointComparator.INSTANCE)
                .containsExactlyInAnyOrder(
                        // since rampingUpInterval has already passed, the full weight is used
                        Endpoint.of("foo.com").withWeight(3000), Endpoint.of("foo1.com"),
                        Endpoint.of("bar.com").withWeight(200)
                );

        // Set an endpoint with the weight which is lower than current weight so scheduling is canceled.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com").withWeight(599),
                                                   Endpoint.of("foo1.com"),
                                                   Endpoint.of("bar.com")));
        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE).containsExactly(
                endpointAndStep(Endpoint.of("bar.com"), 2, steps));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com").withWeight(599), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(200)
                                      );
    }

    @Test
    void rampingUpEndpointsAreRemoved() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        addSecondEndpoints(selector, steps);

        final long window = selector.windowIndex(ticker.get());
        // bar1.com is removed and the weight of bar.com is ramped up.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                   Endpoint.of("bar.com").withWeight(3000)));

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE).containsExactly(
                endpointAndStep(Endpoint.of("bar.com").withWeight(3000), 1, steps));
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(300)
                                      );

        ticker.addAndGet(steps * rampingUpIntervalNanos);
        // bar.com is removed.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com")));
        scheduledJobs.peek().run();

        assertThat(selector.rampingUpWindowsMap).isEmpty();
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com")
                                      );
        assertThat(scheduledFutures).hasSize(2);
        verify(scheduledFutures.poll()).cancel(true);
    }

    @Test
    void sameEndpointsAreProcessed() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        addSecondEndpoints(selector, steps);

        final long window = selector.windowIndex(ticker.get());
        // The three bar.com are converted into onw bar.com with 3000 weight.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                                   Endpoint.of("bar.com"), Endpoint.of("bar.com"),
                                                   Endpoint.of("bar.com"), Endpoint.of("bar1.com")));

        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("bar.com"), 1, steps),
                                            endpointAndStep(Endpoint.of("bar.com"), 1, steps),
                                            endpointAndStep(Endpoint.of("bar.com"), 1, steps),
                                            endpointAndStep(Endpoint.of("bar1.com"), 1, steps)
                                    );
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );
    }

    @Test
    void endpointTimestampsArePrioritized() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        // The three bar.com are converted into onw bar.com with 3000 weight.
        selector.updateCandidates(ImmutableList.of(Endpoint.of("foo.com")));

        ticker.addAndGet(rampingUpIntervalNanos * steps);

        assertThat(selector.rampingUpWindowsMap).isEmpty();
        List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(Endpoint.of("foo.com"));

        // as far as the selector is concerned, the endpoint is added at ticker#get now
        Endpoint endpoint = Endpoint.of("foo.com");
        endpoint = endpoint.withAttr(EndpointAttributeKeys.CREATED_AT_NANOS_KEY, ticker.get());
        selector.updateCandidates(ImmutableList.of(endpoint));

        final long window = selector.windowIndex(ticker.get());
        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(window);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps =
                selector.rampingUpWindowsMap.get(window).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("foo.com"), 1, steps));
        endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(Endpoint.of("foo.com").withWeight(100));
    }

    @Test
    void scheduledIsCanceledWhenEndpointGroupIsClosed() {
        final int steps = 10;
        final RampingUpLoadBalancer<Endpoint> selector = setInitialEndpoints(steps);

        ticker.addAndGet(steps * rampingUpIntervalNanos);

        addSecondEndpoints(selector, steps);
        assertThat(scheduledFutures).hasSize(2);

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(steps));

        final List<Endpoint> newEndpoints = ImmutableList.<Endpoint>builder()
                                                         .addAll(initialEndpoints)
                                                         .addAll(secondEndpoints)
                                                         .add(Endpoint.of("baz.com"))
                                                         .add(Endpoint.of("baz1.com"))
                                                         .build();

        selector.updateCandidates(newEndpoints);
        assertThat(scheduledFutures).hasSize(3);

        selector.close();

        ScheduledFuture<?> scheduledFuture;
        while ((scheduledFuture = scheduledFutures.poll()) != null) {
            verify(scheduledFuture, times(1)).cancel(true);
        }
    }

    @Test
    void shouldReturnNullWhenEndpointGroupIsNotReady() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final EndpointSelector endpointSelector =
                EndpointSelectionStrategy.rampingUp().newSelector(endpointGroup);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(endpointSelector.selectNow(ctx)).isNull();
    }

    private static Stream<Arguments> correctSchedulingParams() {
        final int totalSteps = 5;
        final long intervalNanos = TimeUnit.SECONDS.toNanos(5);
        final long windowNanos = TimeUnit.SECONDS.toNanos(2);
        final long numWindows = (long) Math.ceil(1.0 * intervalNanos / windowNanos);
        return Stream.of(
                Arguments.of(intervalNanos, windowNanos, totalSteps, 0, intervalNanos, 0),
                Arguments.of(intervalNanos, windowNanos, totalSteps, windowNanos - 1,
                             intervalNanos - windowNanos + 1, 0),
                Arguments.of(intervalNanos, windowNanos, totalSteps, windowNanos, intervalNanos, 1),
                Arguments.of(intervalNanos, windowNanos, totalSteps, windowNanos + 1, intervalNanos - 1, 1),
                Arguments.of(intervalNanos, windowNanos, totalSteps, intervalNanos - 1,
                             windowNanos * (numWindows - 1) + 1, numWindows - 1),
                Arguments.of(intervalNanos, windowNanos, totalSteps, intervalNanos, intervalNanos, 0)
        );
    }

    @ParameterizedTest
    @MethodSource("correctSchedulingParams")
    void correctScheduling(long intervalNanos, long windowNanos, int totalSteps,
                           long timePassed, long expectedInitialDelay, long expectedWindow) {
        final RampingUpLoadBalancer<Endpoint> loadBalancer =
                (RampingUpLoadBalancer<Endpoint>)
                        LoadBalancer.<Endpoint>builderForRampingUp(ImmutableList.of())
                                    .weightTransition(weightTransition)
                                    .rampingUpInterval(Duration.ofNanos(intervalNanos))
                                    .rampingUpTaskWindow(Duration.ofNanos(windowNanos))
                                    .totalSteps(totalSteps)
                                    .ticker(ticker::get)
                                    .executor(new ImmediateExecutor())
                                    .build();

        ticker.addAndGet(timePassed);
        loadBalancer.updateCandidates(ImmutableList.of(Endpoint.of("baz.com")));
        assertThat(periodNanos.poll()).isEqualTo(intervalNanos);
        assertThat(initialDelayNanos.poll()).isEqualTo(expectedInitialDelay);
        assertThat(loadBalancer.rampingUpWindowsMap).containsOnlyKeys(expectedWindow);
    }

    private static RampingUpLoadBalancer<Endpoint> setInitialEndpoints(int numberOfSteps) {
        final RampingUpLoadBalancer<Endpoint> loadBalancer = (RampingUpLoadBalancer<Endpoint>)
                LoadBalancer.builderForRampingUp(initialEndpoints)
                            .weightTransition(weightTransition)
                            .rampingUpInterval(Duration.ofNanos(rampingUpIntervalNanos))
                            .rampingUpTaskWindow(Duration.ofNanos(rampingUpTaskWindowNanos))
                            .ticker(ticker::get)
                            .totalSteps(numberOfSteps)
                            .timestampFunction(endpoint -> {
                                if (EndpointAttributeKeys.hasCreatedAtNanos(endpoint)) {
                                    return EndpointAttributeKeys.createdAtNanos(endpoint);
                                } else {
                                    return null;
                                }
                            })
                            .executor(new ImmediateExecutor())
                            .build();

        final ScheduledFuture<?> future = scheduledFutures.peek();
        // We start out with step 1 so the scheduled jobs needs to run (n - 1) times
        for (int i = 0; i < numberOfSteps - 1; i++) {
            scheduledJobs.peek().run();
        }
        ticker.addAndGet(numberOfSteps * rampingUpIntervalNanos);
        verify(future).cancel(anyBoolean());
        periodNanos.clear();
        initialDelayNanos.clear();

        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(loadBalancer);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com")
                                      );
        return loadBalancer;
    }

    private static List<Endpoint> endpointsFromSelectorEntry(RampingUpLoadBalancer<Endpoint> selector) {
        final WeightedRandomLoadBalancer<Weighted> randomLoadBalancer =
                (WeightedRandomLoadBalancer<Weighted>) selector.weightedRandomLoadBalancer();
        return randomLoadBalancer.entries()
                                 .stream()
                                 .map(ctx -> {
                                     final Weighted weighted = ctx.get();
                                     if (weighted instanceof Endpoint) {
                                         return (Endpoint) weighted;
                                     } else {
                                         assertThat(weighted).isInstanceOf(WeightedObject.class);
                                         //noinspection unchecked
                                         final Endpoint endpoint = ((WeightedObject<Endpoint>) weighted).get();
                                         return endpoint.withWeight(weighted.weight());
                                     }
                                 })
                                 .collect(Collectors.toList());
    }

    private static void addSecondEndpoints(RampingUpLoadBalancer<Endpoint> selector, int steps) {
        final List<Endpoint> newEndpoints = ImmutableList.<Endpoint>builder()
                                                         .addAll(initialEndpoints)
                                                         .addAll(secondEndpoints)
                                                         .build();
        final long windowIndex = selector.windowIndex(ticker.get());
        selector.updateCandidates(newEndpoints);
        assertThat(selector.rampingUpWindowsMap).containsOnlyKeys(windowIndex);
        final Set<CandidateAndStep<Endpoint>> endpointAndSteps =
                selector.rampingUpWindowsMap.get(windowIndex).candidateAndSteps();
        assertThat(endpointAndSteps).usingElementComparator(EndpointAndStepComparator.INSTANCE)
                                    .containsExactlyInAnyOrder(
                                            endpointAndStep(Endpoint.of("bar.com"), 1, steps),
                                            endpointAndStep(Endpoint.of("bar1.com"), 1, steps));
        final List<Endpoint> endpointsFromEntry = endpointsFromSelectorEntry(selector);
        assertThat(endpointsFromEntry).usingElementComparator(EndpointComparator.INSTANCE)
                                      .containsExactlyInAnyOrder(
                                              Endpoint.of("foo.com"), Endpoint.of("foo1.com"),
                                              // 1000 * (1 / 10) => weight * (step / numberOfSteps)
                                              Endpoint.of("bar.com").withWeight(100),
                                              Endpoint.of("bar1.com").withWeight(100)
                                      );
    }

    private static CandidateAndStep<Endpoint> endpointAndStep(Endpoint endpoint, int step, int totalSteps) {
        return new CandidateAndStep<>(endpoint, Endpoint::weight, weightTransition, step, totalSteps);
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
    private enum EndpointAndStepComparator implements Comparator<CandidateAndStep<Endpoint>> {

        INSTANCE;

        @Override
        public int compare(CandidateAndStep<Endpoint> o1, CandidateAndStep<Endpoint> o2) {
            final Endpoint endpoint1 = o1.candidate();
            final Endpoint endpoint2 = o2.candidate();
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
            initialDelayNanos.add(unit.toNanos(initialDelay));
            periodNanos.add(unit.toNanos(period));
            final ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
            scheduledFutures.add(scheduledFuture);
            return scheduledFuture;
        }
    }
}
