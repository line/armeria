/*
 * Copyright 2023 LINE Corporation
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

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.EventExecutor;

class SubscribeOnStreamMessageTest {

    @RegisterExtension
    static EventLoopExtension eventLoop1 = new EventLoopExtension();

    @RegisterExtension
    static EventLoopExtension eventLoop2 = new EventLoopExtension();

    @Test
    void completeCase() {
        final EventLoopCheckingStreamMessage<Integer> upstream =
                new EventLoopCheckingStreamMessage<>(eventLoop1.get());
        final Deque<String> acc = new ConcurrentLinkedDeque<>();
        upstream.subscribeOn(eventLoop1.get())
                .subscribe(new EventLoopCheckingSubscriber<>(acc), eventLoop2.get());
        upstream.write(1);
        upstream.close();

        await().untilAsserted(() -> assertThat(acc).containsExactlyElementsOf(
                ImmutableList.of("onSubscribe", "1", "onComplete")));
    }

    @Test
    void errorCase() {
        final EventLoopCheckingStreamMessage<Integer> upstream =
                new EventLoopCheckingStreamMessage<>(eventLoop1.get());
        final Deque<String> acc = new ConcurrentLinkedDeque<>();
        upstream.subscribeOn(eventLoop1.get())
                .subscribe(new EventLoopCheckingSubscriber<>(acc), eventLoop2.get());
        upstream.write(1);
        upstream.close(new Throwable());

        await().untilAsserted(() -> assertThat(acc).containsExactlyElementsOf(
                ImmutableList.of("onSubscribe", "1", "onError")));
    }

    static class EventLoopCheckingStreamMessage<T> extends DefaultStreamMessage<T> {

        private final EventExecutor eventLoop;

        EventLoopCheckingStreamMessage(EventExecutor eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        protected void subscribe0(EventExecutor executor, SubscriptionOption[] options) {
            assertThat(eventLoop.inEventLoop()).isTrue();
        }

        @Override
        protected void onRequest(long n) {
            assert eventLoop.inEventLoop();
        }
    }

    static class EventLoopCheckingSubscriber<T> implements Subscriber<T> {

        private final Deque<String> acc;

        EventLoopCheckingSubscriber(Deque<String> acc) {
            this.acc = acc;
        }

        @Override
        public void onSubscribe(Subscription s) {
            assertThat(eventLoop2.get().inEventLoop()).isTrue();
            s.request(Long.MAX_VALUE);
            acc.add("onSubscribe");
        }

        @Override
        public void onNext(T t) {
            assertThat(eventLoop2.get().inEventLoop()).isTrue();
            acc.add(t.toString());
        }

        @Override
        public void onError(Throwable t) {
            assertThat(eventLoop2.get().inEventLoop()).isTrue();
            acc.add("onError");
        }

        @Override
        public void onComplete() {
            assertThat(eventLoop2.get().inEventLoop()).isTrue();
            acc.add("onComplete");
        }
    }
}
