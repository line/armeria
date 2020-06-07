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

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;

class PooledHttpRequestTest {

    @Test
    void aggregateWithPooledObjects() {
        try (PooledAggregatedHttpRequest agg =
                     PooledHttpRequest.of(request()).aggregateWithPooledObjects().join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregateWithPooledObjects_executor() {
        try (PooledAggregatedHttpRequest agg =
                     PooledHttpRequest.of(request())
                                      .aggregateWithPooledObjects(CommonPools.workerGroup().next())
                                      .join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregateWithPooledObjects_alloc() {
        try (PooledAggregatedHttpRequest agg =
                     PooledHttpRequest.of(request())
                                      .aggregateWithPooledObjects(ByteBufAllocator.DEFAULT)
                                      .join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregateWithPooledObjects_executorAlloc() {
        try (PooledAggregatedHttpRequest agg =
                     PooledHttpRequest.of(request())
                                      .aggregateWithPooledObjects(CommonPools.workerGroup().next(),
                                                                  ByteBufAllocator.DEFAULT).join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregate() {
        final AggregatedHttpRequest agg = PooledHttpRequest.of(request()).aggregate().join();
        assertThat(agg.content()).isNotInstanceOf(PooledHttpData.class);
    }

    @Test
    void aggregate_exeutor() {
        final AggregatedHttpRequest agg = PooledHttpRequest.of(request())
                                                           .aggregate(CommonPools.workerGroup().next()).join();
        assertThat(agg.content()).isNotInstanceOf(PooledHttpData.class);
    }

    private static HttpRequest request() {
        return HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"),
                              PooledHttpData.wrap(ByteBufUtil.writeAscii(ByteBufAllocator.DEFAULT, "foo")),
                              PooledHttpData.wrap(ByteBufUtil.writeAscii(ByteBufAllocator.DEFAULT, "bar")));
    }
}
