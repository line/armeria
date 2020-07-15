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
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.unsafe.PooledHttpData;

import io.netty.buffer.ByteBufHolder;

class FilteredStreamMessageTest {

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void withPooledObjects(boolean filterSupportsPooledObjects, boolean subscribedWithPooledObjects,
                           int expectedRefCntInFilter, int expectedRefCntInOnNext) {
        final PooledHttpData data = PooledHttpData.wrap(newPooledBuffer()).withEndOfStream();
        final DefaultStreamMessage<PooledHttpData> stream = new DefaultStreamMessage<>();
        stream.write(data);
        stream.close();

        final FilteredStreamMessage<PooledHttpData, PooledHttpData> filtered =
                new FilteredStreamMessage<PooledHttpData, PooledHttpData>(stream, filterSupportsPooledObjects) {
                    @Override
                    protected PooledHttpData filter(PooledHttpData obj) {
                        assertThat(data.refCnt()).isEqualTo(expectedRefCntInFilter);
                        return obj;
                    }
                };

        final AtomicBoolean completed = new AtomicBoolean();
        final SubscriptionOption[] options = subscriptionOptions(subscribedWithPooledObjects);
        filtered.subscribe(new Subscriber<PooledHttpData>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(PooledHttpData b) {
                assertThat(data.refCnt()).isEqualTo(expectedRefCntInOnNext);
                subscription.cancel();
                b.release();
                completed.set(true);
            }

            @Override
            public void onError(Throwable t) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, options);

        await().untilAsserted(() -> assertThat(completed).isTrue());
    }

    @Test
    void notifyCancellation() {
        final PooledHttpData data = PooledHttpData.wrap(newPooledBuffer()).withEndOfStream();
        final DefaultStreamMessage<ByteBufHolder> stream = new DefaultStreamMessage<>();
        stream.write(data);
        stream.close();

        final FilteredStreamMessage<ByteBufHolder, ByteBufHolder> filtered =
                new FilteredStreamMessage<ByteBufHolder, ByteBufHolder>(stream) {
                    @Override
                    protected ByteBufHolder filter(ByteBufHolder obj) {
                        return obj;
                    }
                };
        SubscriptionOptionTest.notifyCancellation(filtered);
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
