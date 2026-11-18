/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.CharsetUtil;

class AggregatedHttpRequestDuplicatorTest {

    @Test
    void gracefulCloseReleasesOriginalOnceAndKeepsIssuedRequestUsable() {
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer();
        content.writeCharSequence("body", CharsetUtil.UTF_8);
        final AggregatedHttpRequestDuplicator duplicator = duplicator(content);
        final HttpRequest request = duplicator.duplicate();
        duplicator.close();
        duplicator.close();
        duplicator.abort();
        duplicator.abort(new AnticipatedException());
        assertThat(content.refCnt()).isEqualTo(1);
        assertThat(request.aggregate().join().contentUtf8()).isEqualTo("body");
        assertThat(content.refCnt()).isZero();
        assertThatThrownBy(duplicator::duplicate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateRacingCloseEitherRetainsContentOrRejectsDuplication() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                final ByteBuf content = ByteBufAllocator.DEFAULT.buffer();
                content.writeCharSequence("body", CharsetUtil.UTF_8);
                final AggregatedHttpRequestDuplicator duplicator = duplicator(content);
                final CountDownLatch start = new CountDownLatch(1);
                final Future<HttpRequest> duplicate = executor.submit(() -> {
                    start.await();
                    return duplicator.duplicate();
                });
                final Future<?> close = executor.submit(() -> {
                    start.await();
                    duplicator.close();
                    return null;
                });
                start.countDown();
                close.get(5, TimeUnit.SECONDS);
                try {
                    final HttpRequest request = duplicate.get(5, TimeUnit.SECONDS);
                    assertThat(request.aggregate().get(5, TimeUnit.SECONDS).contentUtf8()).isEqualTo("body");
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                }
                duplicator.abort(new AnticipatedException());
                assertThat(content.refCnt()).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static AggregatedHttpRequestDuplicator duplicator(ByteBuf content) {
        return new AggregatedHttpRequestDuplicator(AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/"), HttpData.wrap(content)));
    }
}
