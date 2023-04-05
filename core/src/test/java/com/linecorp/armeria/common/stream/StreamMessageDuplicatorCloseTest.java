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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * This test checks that the duplicator does not release the elements that it holds until all subscribers are
 * received all element via {@link Subscriber#onNext(Object)}.
 * Previously, the duplicator has a bug that releases the elements when the duplicator is closed even though
 * there's a subscriber that does not subscribe to the child stream message yet.
 * See <a href="https://github.com/line/armeria/pull/3337">
 * Fix not to release elements before subscribed in duplicator</a>
 */
class StreamMessageDuplicatorCloseTest {

    @Test
    void closedDuplicator_elementsAreNotReleasedUntilSubscribedByAllSubscribers() {
        final DefaultStreamMessage<HttpData> publisher = new DefaultStreamMessage<>();
        final ArrayList<ByteBuf> byteBufs = new ArrayList<>(60);
        for (int i = 0; i < 60; i++) { // More than 50 that is the REQUEST_REMOVAL_THRESHOLD.
            final ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(4).writeInt(i);
            byteBufs.add(byteBuf);
            publisher.write(HttpData.wrap(byteBuf));
            assertThat(byteBuf.refCnt()).isOne();
        }
        publisher.close();

        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final StreamMessage<HttpData> first = duplicator.duplicate();
        final StreamMessage<HttpData> second = duplicator.duplicate();

        duplicator.close();

        final Queue<HttpData> firstQueue = new LinkedList<>();
        first.subscribe(new QueueingSubscriber(firstQueue));

        for (int i = 0; i < 60; i++) {
            assertThat(firstQueue.poll().byteBuf().readInt()).isEqualTo(i);
        }

        for (int i = 0; i < 60; i++) {
            assertThat(byteBufs.get(i).refCnt()).isOne();
        }

        final Queue<HttpData> secondQueue = new LinkedList<>();
        second.subscribe(new QueueingSubscriber(secondQueue));

        for (int i = 0; i < 60; i++) {
            assertThat(secondQueue.poll().byteBuf().readInt()).isEqualTo(i);
        }

        for (int i = 0; i < 60; i++) {
            assertThat(byteBufs.get(i).refCnt()).isZero();
        }
    }

    private static final class QueueingSubscriber implements Subscriber<HttpData> {

        private final Queue<HttpData> queue;

        QueueingSubscriber(Queue<HttpData> queue) {
            this.queue = queue;
        }

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData httpData) {
            queue.add(httpData);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
