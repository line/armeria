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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.linecorp.armeria.internal.testing.BlockingUtils;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.EventExecutor;
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
                BlockingUtils.blockingRun(() -> latch.await());
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

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void raceBetweenCollectAndAbort_startCollectFirst_eventLoopCollectFirst(
            FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        // Execute collect() first on the event loop.
        final TestEventExecutor eventExecutor = new TestEventExecutor(eventLoop.get(), 2, false);

        final CompletableFuture<List<Integer>> collectionFuture = stream.collect(eventExecutor);
        assertThat(eventExecutor.numPendingTasks()).isOne();
        assertThat(stream.isComplete()).isFalse();

        stream.abort();

        // The race result:
        // - collect() should win the race and return the list successfully.
        // - abort() tries to clean up resources but nothing remains to clean.
        assertThat(collectionFuture.join()).isInstanceOf(List.class);
        assertThatCode(() -> {
            stream.whenComplete().join();
        }).doesNotThrowAnyException();
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void raceBetweenCollectAndAbort_startCollectFirst_eventLoopAbortFirst(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        // Execute abort() first on the event loop.
        final TestEventExecutor eventExecutor = new TestEventExecutor(eventLoop.get(), 2, true);

        final CompletableFuture<List<Integer>> collectionFuture = stream.collect(eventExecutor);
        assertThat(eventExecutor.numPendingTasks()).isOne();
        assertThat(stream.isComplete()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        stream.abort(abortCause);

        // The race result:
        // - collect() returns a future which is exceptionally completed with abortCause.
        // - abort() cleans up the resources and completes whenComplete() exceptionally.
        assertThatThrownBy(collectionFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);

        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void raceBetweenSubscribeAndAbort_startSubscribeFirst_eventLoopSubscribeFirst(
            FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        // Execute subscribe() first on the event loop.
        final TestEventExecutor eventExecutor = new TestEventExecutor(eventLoop.get(), 2, false);

        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        stream.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
            }

            @Override
            public void onNext(Integer integer) {}

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, eventExecutor);

        assertThat(eventExecutor.numPendingTasks()).isOne();
        assertThat(stream.isComplete()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        stream.abort(abortCause);

        // The race result:
        // - subscribe() finishes successfully.
        // - abort() cleans up the resources and propagates abortCause via onError().
        await().untilAsserted(() -> {
            assertThat(subscriptionRef).hasValue(stream);
        });

        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);
        assertThat(causeRef).hasValue(abortCause);
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void raceBetweenSubscribeAndAbort_startSubscribeFirst_eventLoopAbortFirst(
            FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        // Execute abort() first on the event loop.
        final TestEventExecutor eventExecutor = new TestEventExecutor(eventLoop.get(), 2, true);

        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        stream.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
            }

            @Override
            public void onNext(Integer integer) {}

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, eventExecutor);

        assertThat(eventExecutor.numPendingTasks()).isOne();
        assertThat(stream.isComplete()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        stream.abort(abortCause);

        // The race result:
        // - subscribe() is aborted with NoopSubscription.
        // - abort() cleans the resources and completes whenCompletes() exceptionally with abortCause.
        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);
        await().untilAsserted(() -> {
            assertThat(subscriptionRef).hasValue(NoopSubscription.get());
            assertThat(causeRef).hasValue(abortCause);
        });
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void raceBetweenCollectAndAbort_startAbortFirst(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        stream.abort(abortCause);
        final CompletableFuture<List<Integer>> collectionFuture = stream.collect();

        // The race result:
        // - collect() fails with abortCause synchronously.
        // - abort() cleans up the resources synchronously.
        assertThat(collectionFuture).isCompletedExceptionally();
        assertThatThrownBy(collectionFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);

        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void abortOnSubscribe(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        stream.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                stream.abort(abortCause);
            }

            @Override
            public void onNext(Integer integer) {}

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);
        assertThat(causeRef).hasValue(abortCause);
        assertThat(completed).isFalse();
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void abortOnNext(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        stream.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(Integer integer) {
                stream.abort(abortCause);
            }

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        if (stream instanceof OneElementFixedStreamMessage) {
            // One element was published before the abortion.
            assertThatCode(stream.whenComplete()::join)
                    .doesNotThrowAnyException();
            assertThat(causeRef).hasValue(null);
            assertThat(completed).isTrue();
        } else {
            assertThatThrownBy(stream.whenComplete()::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCause(abortCause);
            assertThat(causeRef).hasValue(abortCause);
            assertThat(completed).isFalse();
        }
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void abortOnComplete(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        stream.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Integer integer) {}

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {
                completed.set(true);
                stream.abort(abortCause);
            }
        });

        // abort() performs nothing when all elements are published.
        assertThatCode(stream.whenComplete()::join)
                .doesNotThrowAnyException();
        assertThat(causeRef).hasValue(null);
        assertThat(completed).isTrue();
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void abortOnError(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        final AnticipatedException onNextCause = new AnticipatedException();
        final AnticipatedException abortCause = new AnticipatedException();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicInteger errorCount = new AtomicInteger();
        stream.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Integer integer) {
                // Make onError() get invoked.
                throw onNextCause;
            }

            @Override
            public void onError(Throwable t) {
                errorCount.getAndIncrement();
                causeRef.set(t);
                stream.abort(abortCause);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(onNextCause);
        assertThat(causeRef).hasValue(onNextCause);
        assertThat(completed).isFalse();
        // Should invoke onError() exactly once.
        assertThat(errorCount).hasValue(1);
    }

    @ArgumentsSource(FixedStreamMessageProvider.class)
    @ParameterizedTest
    void doubleAbort(FixedStreamMessage<Integer> stream) {
        assumeThat(stream.isEmpty()).isFalse();

        final AnticipatedException abortCause = new AnticipatedException();
        stream.abort(abortCause);
        assertThatThrownBy(stream.whenComplete()::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(abortCause);
        // Should perform nothing
        stream.abort();
    }

    private static class TestEventExecutor extends EventExecutorWrapper {
        private final Deque<Runnable> pendingTasks = new ArrayDeque<>();
        private int latchCount;
        private final boolean reverseExecution;

        TestEventExecutor(EventExecutor delegate, int latchCount, boolean reverseExecution) {
            super(delegate);
            this.latchCount = latchCount;
            this.reverseExecution = reverseExecution;
        }

        @Override
        public synchronized void execute(Runnable command) {
            if (--latchCount >= 0) {
                if (reverseExecution) {
                    pendingTasks.addFirst(command);
                } else {
                    pendingTasks.addLast(command);
                }
                if (latchCount == 0) {
                    for (Runnable task : pendingTasks) {
                        super.execute(task);
                    }
                }
            } else {
                super.execute(command);
            }
        }

        int numPendingTasks() {
            return pendingTasks.size();
        }
    }

    private static class FixedStreamMessageProvider implements ArgumentsProvider {

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
