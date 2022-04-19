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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyTest.EndpointComparator;
import com.linecorp.armeria.client.endpoint.WeightedRandomDistributionEndpointSelector.Entry;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.Exceptions;

final class WeightedRandomDistributionEndpointSelectorTest {

    @Test
    void zeroWeightFiltered() {
        final Endpoint foo = Endpoint.of("foo.com").withWeight(0);
        final Endpoint bar = Endpoint.of("bar.com").withWeight(0);
        final List<Endpoint> endpoints = ImmutableList.of(foo, bar);
        final WeightedRandomDistributionEndpointSelector
                selector = new WeightedRandomDistributionEndpointSelector(endpoints);
        assertThat(selector.selectEndpoint()).isNull();
    }

    @Test
    void everyEndpointIsSelectedAsManyAsItsWeightInOneTurn() {
        final Endpoint foo = Endpoint.of("foo.com").withWeight(3);
        final Endpoint bar = Endpoint.of("bar.com").withWeight(2);
        final Endpoint baz = Endpoint.of("baz.com").withWeight(1);
        final List<Endpoint> endpoints = ImmutableList.of(foo, bar, baz);
        final WeightedRandomDistributionEndpointSelector
                selector = new WeightedRandomDistributionEndpointSelector(endpoints);
        for (int i = 0; i < 1000; i++) {
            final ImmutableList.Builder<Endpoint> builder = ImmutableList.builder();
            // The sum of weight is 6. Every endpoint is selected as many as its weight.
            for (int j = 0; j < 6; j++) {
                builder.add(selector.selectEndpoint());
            }
            final List<Endpoint> selected = builder.build();
            assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsExactlyInAnyOrder(
                    foo, foo, foo, bar, bar, baz
            );
        }
    }

    @Test
    void resetEntriesWhenAllEntriesAreFull() throws InterruptedException {
        final int concurrency = 16;
        final CountDownLatch startLatch = new CountDownLatch(concurrency);
        final CountDownLatch checkLatch = new CountDownLatch(concurrency);
        final CountDownLatch finalLatch = new CountDownLatch(concurrency);
        final Endpoint foo = Endpoint.of("foo.com").withWeight(1000);
        final Endpoint bar = Endpoint.of("bar.com").withWeight(2000);
        final Endpoint qux = Endpoint.of("qux.com").withWeight(3000);
        final List<Endpoint> endpoints = ImmutableList.of(foo, bar, qux);
        final WeightedRandomDistributionEndpointSelector
                selector = new WeightedRandomDistributionEndpointSelector(endpoints);
        final int totalWeight = 6000;
        for (int i = 0; i < concurrency; i++) {
            CommonPools.blockingTaskExecutor().execute(() -> {
                try {
                    startLatch.countDown();
                    startLatch.await();

                    for (int count = 0; count < totalWeight * concurrency; count++) {
                        assertThat(selector.selectEndpoint()).isNotNull();
                    }
                    checkLatch.countDown();
                    checkLatch.await();

                    int sum = selector.entries().stream().mapToInt(Entry::counter).sum();
                    // Since all entries were full, `Entry.counter()` should be reset.
                    assertThat(sum).isZero();

                    for (int count = 0; count < totalWeight * concurrency; count++) {
                        assertThat(selector.selectEndpoint()).isNotNull();
                    }
                    finalLatch.countDown();
                    finalLatch.await();

                    sum = selector.entries().stream().mapToInt(Entry::counter).sum();
                    // Since all entries were full, `Entry.counter()` should be reset.
                    assertThat(sum).isZero();
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        finalLatch.await();
        final int sum = selector.entries().stream().mapToInt(Entry::counter).sum();
        assertThat(sum).isZero();
    }
}
