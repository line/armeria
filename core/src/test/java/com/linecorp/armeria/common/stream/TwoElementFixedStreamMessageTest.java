/*
 * Copyright 2019 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopGroups;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

class TwoElementFixedStreamMessageTest {

    @Test
    void cancelOnFirstElement() {
        final ByteBuf obj1 = newBuffer("obj1");
        final ByteBuf obj2 = newBuffer("obj2");
        final TwoElementFixedStreamMessage<HttpData> streamMessage =
                new TwoElementFixedStreamMessage<>(HttpData.wrap(obj1), HttpData.wrap(obj2));
        streamMessage.subscribe(new CancelSubscriber(1), EventLoopGroups.directEventLoop(),
                                SubscriptionOption.WITH_POOLED_OBJECTS);

        assertThat(obj1.refCnt()).isZero();
        assertThat(obj2.refCnt()).isZero();
    }

    @Test
    void cancelOnSecondElement() {
        final ByteBuf obj1 = newBuffer("obj1");
        final ByteBuf obj2 = newBuffer("obj2");
        final TwoElementFixedStreamMessage<HttpData> streamMessage =
                new TwoElementFixedStreamMessage<>(HttpData.wrap(obj1), HttpData.wrap(obj2));
        streamMessage.subscribe(new CancelSubscriber(2), EventLoopGroups.directEventLoop(),
                                SubscriptionOption.WITH_POOLED_OBJECTS);

        assertThat(obj1.refCnt()).isZero();
        assertThat(obj2.refCnt()).isZero();
    }

    private static ByteBuf newBuffer(String content) {
        return ByteBufAllocator.DEFAULT.buffer().writeBytes(content.getBytes());
    }

    private static class CancelSubscriber implements Subscriber<HttpData> {
        private final int cancelCallingSequence;
        private int currentSequence = 1;

        @Nullable
        private Subscription subscription;

        CancelSubscriber(int cancelCallingSequence) {
            this.cancelCallingSequence = cancelCallingSequence;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData data) {
            data.close();
            if (currentSequence++ == cancelCallingSequence) {
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
