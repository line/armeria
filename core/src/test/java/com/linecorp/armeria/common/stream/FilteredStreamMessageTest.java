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
import static com.linecorp.armeria.common.stream.SubscriptionOptionTest.subscriptionOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;

class FilteredStreamMessageTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void withPooledObjects(boolean filterSupportsPooledObjects, boolean subscribedWithPooledObjects,
                           int expectedRefCntInFilter, int expectedRefCntInOnNext) {
        final ByteBuf buf = newPooledBuffer();
        final HttpData data = HttpData.wrap(buf).withEndOfStream();
        final DefaultStreamMessage<HttpData> stream = new DefaultStreamMessage<>();
        stream.write(data);
        stream.close();

        final FilteredStreamMessage<HttpData, HttpData> filtered =
                new FilteredStreamMessage<HttpData, HttpData>(stream, filterSupportsPooledObjects) {
                    @Override
                    protected HttpData filter(HttpData obj) {
                        assertThat(buf.refCnt()).isEqualTo(expectedRefCntInFilter);
                        return obj;
                    }
                };

        final AtomicBoolean completed = new AtomicBoolean();
        final SubscriptionOption[] options = subscriptionOptions(subscribedWithPooledObjects);
        filtered.subscribe(new Subscriber<HttpData>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpData b) {
                assertThat(buf.refCnt()).isEqualTo(expectedRefCntInOnNext);
                subscription.cancel();
                b.close();
                completed.set(true);
            }

            @Override
            public void onError(Throwable t) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail("unexpected onError()", t);
            }

            @Override
            public void onComplete() {
                fail("unexpected onComplete()");
            }
        }, options);

        await().untilAsserted(() -> assertThat(completed).isTrue());
    }

    @Test
    void notifyCancellation() {
        final ByteBuf buf = newPooledBuffer();
        final HttpData data = HttpData.wrap(buf).withEndOfStream();
        final DefaultStreamMessage<HttpData> stream = new DefaultStreamMessage<>();
        stream.write(data);
        stream.close();

        final FilteredStreamMessage<HttpData, HttpData> filtered =
                new FilteredStreamMessage<HttpData, HttpData>(stream) {
                    @Override
                    protected HttpData filter(HttpData obj) {
                        return obj;
                    }
                };
        SubscriptionOptionTest.notifyCancellation(buf, filtered);
    }

    @Test
    void errorPropagation() {
        final EventLoop eventLoop = eventLoopExtension.get();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final DefaultStreamMessage<Integer> streamMessage = new DefaultStreamMessage<>();
        streamMessage.write(1);
        streamMessage.write(2);
        streamMessage.close();

        final IllegalStateException cause = new IllegalStateException();
        final FilteredStreamMessage<Integer, Integer> filtered =
                new FilteredStreamMessage<Integer, Integer>(streamMessage) {
                    @Override
                    protected Integer filter(Integer obj) {
                        throw cause;
                    }
                };

        eventLoop.execute(() -> {
            filtered.subscribe(new Subscriber<Integer>() {
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
                public void onComplete() {}
            }, eventLoop, SubscriptionOption.NOTIFY_CANCELLATION);
        });

        await().untilAtomic(causeRef, Matchers.notNullValue());
        assertThat(causeRef.get()).isEqualTo(cause);
    }

    private static class ParametersProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(Arguments.of(true, false, 1, 0),
                             Arguments.of(true, true, 1, 1),
                             // Because filterSupportsPooledObjects is false, the data is already released
                             // even though subscribedWithPooledObjects is true.
                             Arguments.of(false, true, 0, 0),
                             Arguments.of(false, false, 0, 0));
        }
    }
}
