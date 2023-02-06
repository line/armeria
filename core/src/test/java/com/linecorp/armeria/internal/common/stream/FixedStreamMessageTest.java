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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.ImmediateEventExecutor;

class FixedStreamMessageTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void spec_306_requestAfterCancel(StreamMessage<Integer> stream) throws InterruptedException {
        final CompletableFuture<Subscription> subscriptionFuture = new CompletableFuture<>();
        final AtomicInteger received = new AtomicInteger();
        stream.subscribe(new Subscriber<Integer>() {
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

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void raceBetweenSubscriptionAndAbort(StreamMessage<Integer> stream) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        stream.subscribe(new Subscriber<Integer>() {

            @Override
            public void onSubscribe(Subscription s) {
                try {
                    // Wait for `abort()` to be called.
                    latch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onNext(Integer integer) {}

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, eventLoop.get());

        final AnticipatedException abortCause = new AnticipatedException("Abort a fixed stream");
        stream.abort(abortCause);
        latch.countDown();

        // EmptyStreamMessage performs nothing.
        if (!stream.isEmpty()) {
            await().untilAsserted(() -> assertThat(causeRef).hasValue(abortCause));
            assertThatThrownBy(() -> stream.whenComplete().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(abortCause);
        }
    }

    private static final class FixedStreamMessageProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final AggregatingStreamMessage<Integer> aggregatingStreamMessage =
                    new AggregatingStreamMessage<>(5);
            aggregatingStreamMessage.write(1);
            aggregatingStreamMessage.write(2);
            aggregatingStreamMessage.write(3);
            aggregatingStreamMessage.write(4);
            aggregatingStreamMessage.close();
            return Stream.of(StreamMessage.of(),           // EmptyFixedStreamMessage
                             StreamMessage.of(1),          // OneElementFixedStreamMessage
                             StreamMessage.of(1, 2),       // TwoElementFixedStreamMessage
                             StreamMessage.of(1, 2, 3),    // ThreeElementFixedStreamMessage
                             StreamMessage.of(1, 2, 3, 4), // RegularFixedStreamMessage
                             aggregatingStreamMessage)
                         .map(Arguments::of);
        }
    }
}
