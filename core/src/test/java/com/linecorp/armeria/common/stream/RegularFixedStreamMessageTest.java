/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.util.concurrent.ImmediateEventExecutor;

class RegularFixedStreamMessageTest {

    @Test
    void demandTest() {
        final int size = 6;
        String[] strings = new String[size];
        Arrays.fill(strings, "foo");

        final AtomicInteger counter = new AtomicInteger();
        final RegularFixedStreamMessage<String> streamMessage = new RegularFixedStreamMessage<>(strings);
        assertThat(streamMessage.demand()).isZero();
        streamMessage.subscribe(new Subscriber<String>() {

            private Subscription s;

            @Override
            public void onSubscribe(Subscription s) {
                this.s = s;
                s.request(3);
            }

            @Override
            public void onNext(String serviceName) {
                switch (counter.getAndIncrement()) {
                    case 0:
                        assertThat(streamMessage.demand()).isEqualTo(2);
                        break;
                    case 1:
                        assertThat(streamMessage.demand()).isOne();
                        break;
                    case 2:
                        assertThat(streamMessage.demand()).isZero();
                        s.request(20);
                        break;
                    case 3:
                        assertThat(streamMessage.demand()).isEqualTo(size - 1);
                        break;
                    case 4:
                        assertThat(streamMessage.demand()).isEqualTo(size - 2);
                        s.request(20);
                        break;
                    case 5:
                        assertThat(streamMessage.demand()).isEqualTo(size - 1);
                        break;
                }
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                assertThat(streamMessage.demand()).isEqualTo(size - 1);
            }
        }, ImmediateEventExecutor.INSTANCE);

        await().untilAsserted(() -> assertThat(streamMessage.demand()).isEqualTo(size - 1));
    }
}
