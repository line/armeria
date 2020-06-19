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

package com.linecorp.armeria.common.unsafe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.testing.DrainingSubscriber;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;

class PooledHttpStreamMessageTest {

    @Test
    void subscribeWithPooledObjects() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribeWithPooledObjects(objects);
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isTrue();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribeWithPooledObjects_options() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribeWithPooledObjects(objects,
                                                                     SubscriptionOption.NOTIFY_CANCELLATION);
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isTrue();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribeWithPooledObjects_executor() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribeWithPooledObjects(objects, CommonPools.workerGroup().next());
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isTrue();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribeWithPooledObjects_executorOptions() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribeWithPooledObjects(objects, CommonPools.workerGroup().next(),
                                                                     SubscriptionOption.NOTIFY_CANCELLATION);
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isTrue();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribe() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribe(objects);
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isFalse();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribe_options() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribe(objects, SubscriptionOption.NOTIFY_CANCELLATION);
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isFalse();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribe_executor() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribe(objects, CommonPools.workerGroup().next());
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isFalse();
                                pooled.close();
                            });
                });
    }

    @Test
    void subscribe_executorOptions() {
        final DrainingSubscriber<HttpObject> objects = new DrainingSubscriber<>();
        PooledHttpResponse.of(response()).subscribe(objects, CommonPools.workerGroup().next(),
                                                    SubscriptionOption.NOTIFY_CANCELLATION);
        assertThat(objects.result().join())
                .filteredOn(HttpData.class::isInstance)
                .allSatisfy(data -> {
                    assertThat(data).isInstanceOfSatisfying(
                            PooledHttpData.class,
                            pooled -> {
                                assertThat(pooled.content().isDirect()).isFalse();
                                pooled.close();
                            });
                });
    }

    private static HttpResponse response() {
        return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                               PooledHttpData.wrap(ByteBufUtil.writeAscii(ByteBufAllocator.DEFAULT, "foo")),
                               PooledHttpData.wrap(ByteBufUtil.writeAscii(ByteBufAllocator.DEFAULT, "bar")));
    }
}
