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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.concurrent.ImmediateEventExecutor;

class SubscriberThrowingExceptionTest {

    @Test
    void streamCompleteExceptionallyWithCompositeExceptionIfOnErrorThrowsException() {
        final DefaultStreamMessage<Object> stream = new DefaultStreamMessage<>();
        final IllegalStateException illegalStateException = new IllegalStateException();
        final AnticipatedException anticipatedException = new AnticipatedException();
        stream.tryClose(illegalStateException);
        stream.subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(Object o) {}

            @Override
            public void onError(Throwable t) {
                throw anticipatedException;
            }

            @Override
            public void onComplete() {}
        }, ImmediateEventExecutor.INSTANCE);

        final Throwable throwable = catchThrowable(() -> stream.whenComplete().join());
        final Throwable cause = throwable.getCause();
        assertThat(cause).isInstanceOf(CompositeException.class);
        final CompositeException composite = (CompositeException) cause;
        assertThat(composite.getExceptions()).containsExactly(anticipatedException, illegalStateException);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void streamMessages(boolean throwExceptionOnOnSubscribe) {
        final DefaultStreamMessage<HttpData> defaultStreamMessage = new DefaultStreamMessage<>();
        ByteBuf data0 = newUnpooledBuffer();
        defaultStreamMessage.write(HttpData.wrap(data0));
        subscribeAndValidate(defaultStreamMessage, throwExceptionOnOnSubscribe);
        assertThat(data0.refCnt()).isZero();

        data0 = newUnpooledBuffer();
        final StreamMessage<HttpData> oneElement = new OneElementFixedStreamMessage<>(HttpData.wrap(data0));
        subscribeAndValidate(oneElement, throwExceptionOnOnSubscribe);
        assertThat(data0.refCnt()).isZero();

        data0 = newUnpooledBuffer();
        ByteBuf data1 = newUnpooledBuffer();
        final StreamMessage<HttpData> twoElement = new TwoElementFixedStreamMessage<>(HttpData.wrap(data0),
                                                                                      HttpData.wrap(data1));
        subscribeAndValidate(twoElement, throwExceptionOnOnSubscribe);
        assertThat(data0.refCnt()).isZero();
        assertThat(data1.refCnt()).isZero();

        data0 = newUnpooledBuffer();
        data1 = newUnpooledBuffer();
        final ByteBuf data2 = newUnpooledBuffer();
        final StreamMessage<HttpData> regularElement =
                new RegularFixedStreamMessage<>(new HttpData[] {
                        HttpData.wrap(data0), HttpData.wrap(data1), HttpData.wrap(data2)
                });
        subscribeAndValidate(regularElement, throwExceptionOnOnSubscribe);
        assertThat(data0.refCnt()).isZero();
        assertThat(data1.refCnt()).isZero();
        assertThat(data2.refCnt()).isZero();

        final DefaultStreamMessage<HttpData> publisher = new DefaultStreamMessage<>();
        final ByteBuf data3 = newUnpooledBuffer();
        publisher.write(HttpData.wrap(data3));
        final PublisherBasedStreamMessage<Object> publisherBasedStreamMessage =
                new PublisherBasedStreamMessage<>(publisher);
        subscribeAndValidate(publisherBasedStreamMessage, throwExceptionOnOnSubscribe);
        assertThatThrownBy(() -> publisher.whenComplete().join())
                .hasCauseInstanceOf(CancelledSubscriptionException.class);
        await().until(() -> data3.refCnt() == 0);
    }

    private static void subscribeAndValidate(StreamMessage<?> stream, boolean throwExceptionOnOnSubscribe) {
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

    private static class ExceptionThrowingSubscriber implements Subscriber<Object> {

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
