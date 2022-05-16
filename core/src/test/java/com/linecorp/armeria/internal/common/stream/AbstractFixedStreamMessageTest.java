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
 * under the License.
 */

package com.linecorp.armeria.internal.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.ImmediateEventExecutor;

class AbstractFixedStreamMessageTest {

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void demandTest(boolean useArray) {
        final int size = 6;
        final String[] strings = new String[size];
        Arrays.fill(strings, "foo");

        final AtomicInteger counter = new AtomicInteger();
        final AbstractFixedStreamMessage<String> streamMessage;
        if (useArray) {
            streamMessage = new RegularFixedStreamMessage<>(strings);
        } else {
            final AggregatingStreamMessage<String> stream = new AggregatingStreamMessage<>(size);
            for (String string : strings) {
                stream.write(string);
            }
            stream.close();
            streamMessage = stream;
        }
        assertThat(streamMessage.demand()).isZero();
        final AtomicBoolean completed = new AtomicBoolean();
        streamMessage.subscribe(new Subscriber<String>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
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
                        subscription.request(20);
                        break;
                    case 3:
                        assertThat(streamMessage.demand()).isEqualTo(2);
                        break;
                    case 4:
                        assertThat(streamMessage.demand()).isOne();
                        subscription.request(20);
                        break;
                    case 5:
                        assertThat(streamMessage.demand()).isZero();
                        break;
                }
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                assertThat(streamMessage.demand()).isZero();
                completed.set(true);
            }
        }, ImmediateEventExecutor.INSTANCE);

        await().untilTrue(completed);
    }
}
