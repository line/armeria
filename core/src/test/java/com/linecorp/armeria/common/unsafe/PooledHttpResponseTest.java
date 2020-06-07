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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;

class PooledHttpResponseTest {

    @Test
    void aggregateWithPooledObjects() {
        try (PooledAggregatedHttpResponse agg =
                     PooledHttpResponse.of(response()).aggregateWithPooledObjects().join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregateWithPooledObjects_executor() {
        try (PooledAggregatedHttpResponse agg =
                     PooledHttpResponse.of(response())
                                      .aggregateWithPooledObjects(CommonPools.workerGroup().next())
                                      .join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregateWithPooledObjects_alloc() {
        try (PooledAggregatedHttpResponse agg =
                     PooledHttpResponse.of(response())
                                      .aggregateWithPooledObjects(ByteBufAllocator.DEFAULT)
                                      .join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregateWithPooledObjects_executorAlloc() {
        try (PooledAggregatedHttpResponse agg =
                     PooledHttpResponse.of(response())
                                      .aggregateWithPooledObjects(CommonPools.workerGroup().next(),
                                                                  ByteBufAllocator.DEFAULT).join()) {
            assertThat(agg.content().content().isDirect()).isTrue();
        }
    }

    @Test
    void aggregate() {
        final AggregatedHttpResponse agg = PooledHttpResponse.of(response()).aggregate().join();
        assertThat(agg.content()).isNotInstanceOf(PooledHttpData.class);
    }

    @Test
    void aggregate_exeutor() {
        final AggregatedHttpResponse agg =
                PooledHttpResponse.of(response())
                                  .aggregate(CommonPools.workerGroup().next()).join();
        assertThat(agg.content()).isNotInstanceOf(PooledHttpData.class);
    }

    private static HttpResponse response() {
        return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                               PooledHttpData.wrap(ByteBufUtil.writeAscii(ByteBufAllocator.DEFAULT, "foo")),
                               PooledHttpData.wrap(ByteBufUtil.writeAscii(ByteBufAllocator.DEFAULT, "bar")));
    }
}
