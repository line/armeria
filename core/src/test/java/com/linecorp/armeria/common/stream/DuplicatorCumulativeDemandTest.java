/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

final class DuplicatorCumulativeDemandTest {

    @Test
    void cumulativeDemand() throws InterruptedException {
        final AtomicLong demand = new AtomicLong();
        final PublisherBasedStreamMessage<Integer> streamMessage = new PublisherBasedStreamMessage<>(
                s -> s.onSubscribe(new Subscription() {
                    @Override
                    public void request(long n) {
                        demand.addAndGet(n);
                    }

                    @Override
                    public void cancel() {}
                }));
        streamMessage.toDuplicator().duplicate().subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(Integer integer) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });
        await().untilAsserted(() -> assertThat(demand.get()).isEqualTo(1));
        Thread.sleep(100);
        // The demand is still 1.
        assertThat(demand.get()).isEqualTo(1);
    }
}
