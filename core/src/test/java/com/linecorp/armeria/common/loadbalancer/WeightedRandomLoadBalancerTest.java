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
package com.linecorp.armeria.common.loadbalancer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.loadbalancer.RampingUpLoadBalancerTest.EndpointComparator;
import com.linecorp.armeria.common.loadbalancer.WeightedRandomLoadBalancer.CandidateContext;
import com.linecorp.armeria.common.util.Exceptions;

final class WeightedRandomLoadBalancerTest {

    @Test
    void zeroWeightFiltered() {
        final Endpoint foo = Endpoint.of("foo.com").withWeight(0);
        final Endpoint bar = Endpoint.of("bar.com").withWeight(0);
        final List<Endpoint> endpoints = ImmutableList.of(foo, bar);
        final SimpleLoadBalancer<Endpoint> loadBalancer =
                LoadBalancer.ofWeightedRandom(endpoints, Endpoint::weight);
        assertThat(loadBalancer.pick()).isNull();
    }

    @Test
    void everyEndpointIsSelectedAsManyAsItsWeightInOneTurn() {
        final Endpoint foo = Endpoint.of("foo.com").withWeight(3);
        final Endpoint bar = Endpoint.of("bar.com").withWeight(2);
        final Endpoint baz = Endpoint.of("baz.com").withWeight(1);
        final List<Endpoint> endpoints = ImmutableList.of(foo, bar, baz);
        final SimpleLoadBalancer<Endpoint> loadBalancer = LoadBalancer.ofWeightedRandom(
                endpoints, Endpoint::weight);
        for (int i = 0; i < 1000; i++) {
            final ImmutableList.Builder<Endpoint> builder = ImmutableList.builder();
            // The sum of weight is 6. Every endpoint is selected as many as its weight.
            for (int j = 0; j < 6; j++) {
                builder.add(loadBalancer.pick());
            }
            final List<Endpoint> selected = builder.build();
            assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsExactlyInAnyOrder(
                    foo, foo, foo, bar, bar, baz
            );
        }
    }

    @Test
    void resetEntriesWhenAllEntriesAreFull() throws InterruptedException {
        final int concurrency = 4;
        final CountDownLatch startLatch0 = new CountDownLatch(concurrency);
        final CountDownLatch finalLatch0 = new CountDownLatch(concurrency);
        final CountDownLatch startLatch1 = new CountDownLatch(concurrency);
        final CountDownLatch finalLatch1 = new CountDownLatch(concurrency);
        final Endpoint foo = Endpoint.of("foo.com").withWeight(100);
        final Endpoint bar = Endpoint.of("bar.com").withWeight(200);
        final Endpoint qux = Endpoint.of("qux.com").withWeight(300);
        final List<Endpoint> endpoints = ImmutableList.of(foo, bar, qux);
        final WeightedRandomLoadBalancer<Endpoint> loadBalancer =
                (WeightedRandomLoadBalancer<Endpoint>)
                        LoadBalancer.ofWeightedRandom(endpoints, Endpoint::weight);

        final int totalWeight = foo.weight() + bar.weight() + qux.weight();
        for (int i = 0; i < concurrency; i++) {
            CommonPools.blockingTaskExecutor().execute(() -> {
                try {
                    startLatch0.countDown();
                    startLatch0.await();

                    for (int count = 0; count < totalWeight * concurrency; count++) {
                        assertThat(loadBalancer.pick()).isNotNull();
                    }
                    finalLatch0.countDown();
                    finalLatch0.await();

                    final int sum = loadBalancer.entries().stream().mapToInt(CandidateContext::counter).sum();
                    // Since all entries were full, `Entry.counter()` should be reset.
                    assertThat(sum).isZero();

                    startLatch1.countDown();
                    startLatch1.await();
                    for (int count = 0; count < totalWeight * concurrency; count++) {
                        assertThat(loadBalancer.pick()).isNotNull();
                    }
                    finalLatch1.countDown();
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        finalLatch1.await();
        final int sum = loadBalancer.entries().stream().mapToInt(CandidateContext::counter).sum();
        assertThat(sum).isZero();
    }
}
