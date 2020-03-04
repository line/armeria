/*
 * Copyright 2020 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.concurrent.ImmediateEventExecutor;

class SubscriberThrowingExceptionTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void streamMessages(boolean throwExceptionOnOnSubscribe) {
        final DefaultStreamMessage<Object> defaultStreamMessage = new DefaultStreamMessage<>();
        ByteBuf data = newUnpooledBuffer();
        defaultStreamMessage.write(data);
        subscribeAndValidate(defaultStreamMessage, throwExceptionOnOnSubscribe);
        assertThat(data.refCnt()).isZero();

        data = newUnpooledBuffer();
        final StreamMessage<Object> oneElement = new OneElementFixedStreamMessage<>(data);
        subscribeAndValidate(oneElement, throwExceptionOnOnSubscribe);
        assertThat(data.refCnt()).isZero();

        data = newUnpooledBuffer();
        ByteBuf data1 = newUnpooledBuffer();
        final StreamMessage<Object> twoElement = new TwoElementFixedStreamMessage<>(data, data1);
        subscribeAndValidate(twoElement, throwExceptionOnOnSubscribe);
        assertThat(data.refCnt()).isZero();
        assertThat(data1.refCnt()).isZero();

        data = newUnpooledBuffer();
        data1 = newUnpooledBuffer();
        final ByteBuf data2 = newUnpooledBuffer();
        final StreamMessage<Object> regularElement =
                new RegularFixedStreamMessage<>(new ByteBuf[] { data, data1, data2 });
        subscribeAndValidate(regularElement, throwExceptionOnOnSubscribe);
        assertThat(data.refCnt()).isZero();
        assertThat(data1.refCnt()).isZero();
        assertThat(data2.refCnt()).isZero();

        final DefaultStreamMessage<Object> publisher = new DefaultStreamMessage<>();
        data = newUnpooledBuffer();
        publisher.write(data);
        final PublisherBasedStreamMessage<Object> publisherBasedStreamMessage =
                new PublisherBasedStreamMessage<>(publisher);
        subscribeAndValidate(publisherBasedStreamMessage, throwExceptionOnOnSubscribe);
        assertThat(data.refCnt()).isZero();
        assertThatThrownBy(() -> publisher.whenComplete().join())
                .hasCauseInstanceOf(CancelledSubscriptionException.class);
    }

    private void subscribeAndValidate(StreamMessage<Object> stream, boolean throwExceptionOnOnSubscribe) {
        final AtomicReference<Throwable> onErrorCaptor = new AtomicReference<>();
        stream.subscribe(new ExceptionThrowingSubscriber(onErrorCaptor, throwExceptionOnOnSubscribe),
                         ImmediateEventExecutor.INSTANCE);

        final Throwable throwable = catchThrowable(() -> stream.whenComplete().join());
        assertThat(throwable).hasCauseInstanceOf(AnticipatedException.class);
        assertThat(throwable.getCause()).isSameAs(onErrorCaptor.get());
    }

    private static ByteBuf newUnpooledBuffer() {
        return UnpooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }

    private class ExceptionThrowingSubscriber implements Subscriber<Object> {

        private final AtomicReference<Throwable> onErrorCaptor;
        private final boolean throwExceptionOnOnSubscribe;

        ExceptionThrowingSubscriber(AtomicReference<Throwable> onErrorCaptor,
                                    boolean throwExceptionOnOnSubscribe) {
            this.onErrorCaptor = onErrorCaptor;
            this.throwExceptionOnOnSubscribe = throwExceptionOnOnSubscribe;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (throwExceptionOnOnSubscribe) {
                throw new AnticipatedException();
            } else {
                s.request(1);
            }
        }

        @Override
        public void onNext(Object o) {
            throw new AnticipatedException();
        }

        @Override
        public void onError(Throwable t) {
            onErrorCaptor.set(t);
        }

        @Override
        public void onComplete() {}
    }
}
