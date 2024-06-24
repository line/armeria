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
package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.EventLoop;

/**
 * This test checks that the duplicator does not release the elements that it holds until all subscribers are
 * received all element via {@link Subscriber#onNext(Object)}.
 * Previously, the duplicator has a bug that releases the elements when the duplicator is closed even though
 * there's a subscriber that does not subscribe to the child stream message yet.
 * See <a href="https://github.com/line/armeria/pull/3337">
 * Fix not to release elements before subscribed in duplicator</a>
 */
class StreamMessageDuplicatorChildSubscriberTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop1 = new EventLoopExtension();

    @RegisterExtension
    static final EventLoopExtension eventLoop2 = new EventLoopExtension();

    @RegisterExtension
    static final EventLoopExtension eventLoop3 = new EventLoopExtension();

    @Test
    void childSubscriberMethodsMustBeCalledByExecutors() throws InterruptedException {
        final StreamWriter<String> publisher = StreamMessage.streaming();
        publisher.write("foo");
        publisher.abort();

        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(eventLoop1.get());

        final StreamMessage<String> first = duplicator.duplicate();
        final StreamMessage<String> second = duplicator.duplicate();

        duplicator.close();

        final CountDownLatch latch = new CountDownLatch(2);
        final EventLoop executor2 = eventLoop2.get();
        first.subscribe(new ChildSubscriber(executor2, latch), executor2);

        final EventLoop executor3 = eventLoop3.get();
        second.subscribe(new ChildSubscriber(executor3, latch), executor3);
        latch.await();
    }

    private static final class ChildSubscriber implements Subscriber<String> {

        private final EventLoop eventLoop;
        private final CountDownLatch latch;

        ChildSubscriber(EventLoop eventLoop, CountDownLatch latch) {
            this.eventLoop = eventLoop;
            this.latch = latch;
        }

        @Override
        public void onSubscribe(Subscription s) {
            assertThat(eventLoop.inEventLoop()).isTrue();
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String data) {
            assertThat(eventLoop.inEventLoop()).isTrue();
        }

        @Override
        public void onError(Throwable t) {
            assertThat(eventLoop.inEventLoop()).isTrue();
            latch.countDown();
        }

        @Override
        public void onComplete() {
            assertThat(eventLoop.inEventLoop()).isTrue();
            latch.countDown();
        }
    }
}
