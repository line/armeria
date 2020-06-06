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

import static com.linecorp.armeria.common.stream.StreamMessageTest.newPooledBuffer;
import static com.linecorp.armeria.common.stream.SubscriptionOption.NOTIFY_CANCELLATION;
import static com.linecorp.armeria.internal.stream.InternalSubscriptionOption.WITH_POOLED_OBJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.of;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBufHolder;

class SubscriptionOptionTest {

    @ParameterizedTest
    @ArgumentsSource(ByteBufHolderStreamProvider.class)
    void withPooledObjects_true(ByteBufHolder data, StreamMessage<ByteBufHolder> stream) {
        final boolean withPooledObjects = true;
        final int expectedRefCnt = 1;

        withPooledObjects(data, stream, withPooledObjects, expectedRefCnt);
    }

    @ParameterizedTest
    @ArgumentsSource(ByteBufHolderStreamProvider.class)
    void withPooledObjects_false(ByteBufHolder data, StreamMessage<ByteBufHolder> stream) {
        final boolean withPooledObjects = false;
        final int expectedRefCnt = 0;

        withPooledObjects(data, stream, withPooledObjects, expectedRefCnt);
    }

    private static void withPooledObjects(ByteBufHolder data, StreamMessage<ByteBufHolder> stream,
                                          boolean withPooledObjects, int expectedRefCnt) {
        final AtomicBoolean completed = new AtomicBoolean();
        stream.subscribe(new Subscriber<ByteBufHolder>() {
            @Nullable
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBufHolder b) {
                assertThat(data.refCnt()).isEqualTo(expectedRefCnt);
                subscription.cancel();
                b.release();
                completed.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail("onError() invoked unexpectedly");
            }

            @Override
            public void onComplete() {
                fail("onComplete() invoked unexpectedly");
            }
        }, subscriptionOptions(withPooledObjects));

        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(data.refCnt()).isZero();
    }

    @ParameterizedTest
    @ArgumentsSource(ByteBufHolderStreamProvider.class)
    void notifyCancellation(ByteBufHolder unused, StreamMessage<ByteBufHolder> stream) {
        notifyCancellation(stream);
    }

    static void notifyCancellation(StreamMessage<ByteBufHolder> stream) {
        final AtomicBoolean completed = new AtomicBoolean();
        stream.subscribe(new Subscriber<ByteBufHolder>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.cancel();
            }

            @Override
            public void onNext(ByteBufHolder b) {
                fail("onNext() invoked unexpectedly");
            }

            @Override
            public void onError(Throwable t) {
                assertThat(t).isInstanceOf(CancelledSubscriptionException.class);
                completed.set(true);
            }

            @Override
            public void onComplete() {
                fail("onComplete() invoked unexpectedly");
            }
        }, NOTIFY_CANCELLATION);

        await().untilAsserted(() -> assertThat(completed).isTrue());
        await().untilAsserted(() -> assertThat(stream.whenComplete().isCompletedExceptionally()));
    }

    static SubscriptionOption[] subscriptionOptions(boolean subscribedWithPooledObjects) {
        final ArrayList<SubscriptionOption> options = new ArrayList<>(1);
        if (subscribedWithPooledObjects) {
            options.add(WITH_POOLED_OBJECTS);
        }
        return options.toArray(new SubscriptionOption[0]);
    }

    private static class ByteBufHolderStreamProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(defaultStream(), fixedStream(), deferredStream());
        }

        private static Arguments defaultStream() {
            final DefaultStreamMessage<ByteBufHolder> defaultStream = new DefaultStreamMessage<>();
            final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
            defaultStream.write(data);
            defaultStream.close();
            return of(data, defaultStream);
        }

        private static Arguments fixedStream() {
            final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
            final StreamMessage<ByteBufHttpData> fixedStream = StreamMessage.of(data);
            return of(data, fixedStream);
        }

        private static Arguments deferredStream() {
            final DeferredStreamMessage<ByteBufHttpData> deferredStream = new DeferredStreamMessage<>();
            final DefaultStreamMessage<ByteBufHttpData> d = new DefaultStreamMessage<>();
            deferredStream.delegate(d);
            final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
            d.write(data);
            d.close();
            return of(data, deferredStream);
        }
    }
}
