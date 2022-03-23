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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.util.concurrent.ImmediateEventExecutor;

class FixedStreamMessageTest {

    private static final Integer[] EMPTY_INTEGERS = new Integer[0];

    @ArgumentsSource(IntsProvider.class)
    @ParameterizedTest
    void spec_306_requestAfterCancel(List<Integer> nums) throws InterruptedException {
        final Integer[] array = nums.toArray(EMPTY_INTEGERS);
        final StreamMessage<Integer> message = StreamMessage.of(array);
        final CompletableFuture<Subscription> subscriptionFuture = new CompletableFuture<>();
        final AtomicInteger received = new AtomicInteger();
        message.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscriptionFuture.complete(s);
            }

            @Override
            public void onNext(Integer integer) {
               received.getAndIncrement();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        }, ImmediateEventExecutor.INSTANCE);

        final Subscription subscription = subscriptionFuture.join();

        subscription.cancel();
        subscription.request(1);
        // Should not receive any values.
        assertThat(received).hasValue(0);
    }

    private static final class IntsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(ImmutableList.of(),
                             ImmutableList.of(1),
                             ImmutableList.of(1, 2),
                             ImmutableList.of(1, 2, 3),
                             ImmutableList.of(1, 2, 3, 4))
                         .map(Arguments::of);
        }
    }
}
