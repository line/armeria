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

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBufHolder;

class FilteredStreamMessageTest {

    @Test
    void withPooledObjects() {
        boolean filterSupportsPooledObjects = true;
        boolean subscribedWithPooledObjects = false;
        int expectedRefCntInFilter = 1;
        int expectedRefCntInOnNext = 0;

        withPooledObjects(filterSupportsPooledObjects, subscribedWithPooledObjects,
                          expectedRefCntInFilter, expectedRefCntInOnNext);

        filterSupportsPooledObjects = true;
        subscribedWithPooledObjects = true;
        expectedRefCntInFilter = 1;
        expectedRefCntInOnNext = 1;

        withPooledObjects(filterSupportsPooledObjects, subscribedWithPooledObjects,
                          expectedRefCntInFilter, expectedRefCntInOnNext);

        filterSupportsPooledObjects = false;
        subscribedWithPooledObjects = false;
        expectedRefCntInFilter = 0;
        expectedRefCntInOnNext = 0;

        withPooledObjects(filterSupportsPooledObjects, subscribedWithPooledObjects,
                          expectedRefCntInFilter, expectedRefCntInOnNext);

        filterSupportsPooledObjects = false;
        subscribedWithPooledObjects = true;
        expectedRefCntInFilter = 0;
        // Because filterSupportsPooledObjects is false, the data is already released even though
        // subscribedWithPooledObjects is true.
        expectedRefCntInOnNext = 0;

        withPooledObjects(filterSupportsPooledObjects, subscribedWithPooledObjects,
                          expectedRefCntInFilter, expectedRefCntInOnNext);
    }

    private static void withPooledObjects(
            boolean filterSupportsPooledObjects, boolean subscribedWithPooledObjects,
            int refCntInFilter, int refCntInOnNext) {
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        final DefaultStreamMessage<ByteBufHttpData> stream = new DefaultStreamMessage<>();
        stream.write(data);
        stream.close();

        final FilteredStreamMessage<ByteBufHttpData, ByteBufHttpData> filtered =
                new FilteredStreamMessage<ByteBufHttpData, ByteBufHttpData>(stream,
                                                                            filterSupportsPooledObjects) {
                    @Override
                    protected ByteBufHttpData filter(ByteBufHttpData obj) {
                        assertThat(data.refCnt()).isEqualTo(refCntInFilter);
                        return obj;
                    }
                };

        final AtomicBoolean completed = new AtomicBoolean();
        final SubscriptionOption[] options = subscriptionOptions(subscribedWithPooledObjects);
        filtered.subscribe(new Subscriber<ByteBufHttpData>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(ByteBufHttpData b) {
                assertThat(data.refCnt()).isEqualTo(refCntInOnNext);
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
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
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
}
