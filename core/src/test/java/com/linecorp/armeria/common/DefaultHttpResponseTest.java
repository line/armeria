/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

class DefaultHttpResponseTest {

    @Test
    void closeWithAggregatedResponse() {
        // Always headers only.
        final HttpResponseWriter res1 = HttpResponse.streaming();
        res1.close(AggregatedHttpResponse.of(204));
        assertThat(res1.drainAll().join()).containsExactly(ResponseHeaders.of(204));

        // Headers only.
        final HttpResponseWriter res2 = HttpResponse.streaming();
        res2.close(AggregatedHttpResponse.of(ResponseHeaders.of(200)));
        assertThat(res2.drainAll().join()).containsExactly(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 0));

        // Headers and body.
        final HttpResponseWriter res3 = HttpResponse.streaming();
        res3.close(AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.ofUtf8("foo")));
        assertThat(res3.drainAll().join()).containsExactly(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3),
                HttpData.ofUtf8("foo"));

        // Headers, body and trailers.
        final HttpResponseWriter res4 = HttpResponse.streaming();
        res4.close(AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.ofUtf8("bar"),
                                             HttpHeaders.of("x-trailer", true)));
        assertThat(res4.drainAll().join()).containsExactly(
                ResponseHeaders.of(200),
                HttpData.ofUtf8("bar"),
                HttpHeaders.of("x-trailer", true));
    }

    @Test
    void closeMustReleaseAggregatedContent() {
        final HttpResponseWriter res = HttpResponse.streaming();
        final ByteBufHttpData data =
                new ByteBufHttpData(Unpooled.copiedBuffer("foo", StandardCharsets.UTF_8), true);
        res.close();
        res.close(AggregatedHttpResponse.of(ResponseHeaders.of(200), data));
        assertThat(data.refCnt()).isZero();
    }

    /**
     * The aggregation future must be completed even if the response being aggregated has been aborted.
     */
    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void abortedAggregation(boolean executorSpecified, boolean withPooledObjects) {
        final Thread mainThread = Thread.currentThread();
        final HttpResponseWriter res = HttpResponse.streaming();
        final CompletableFuture<AggregatedHttpResponse> future;

        // Practically same execution, but we need to test the both case due to code duplication.
        if (executorSpecified) {
            if (withPooledObjects) {
                future = res.aggregateWithPooledObjects(
                        CommonPools.workerGroup().next(), PooledByteBufAllocator.DEFAULT);
            } else {
                future = res.aggregate(CommonPools.workerGroup().next());
            }
        } else {
            if (withPooledObjects) {
                future = res.aggregateWithPooledObjects(PooledByteBufAllocator.DEFAULT);
            } else {
                future = res.aggregate();
            }
        }

        final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        assertThatThrownBy(() -> {
            final CompletableFuture<AggregatedHttpResponse> f =
                    future.whenComplete((unused, cause) -> callbackThread.set(Thread.currentThread()));
            res.abort();
            f.join();
        }).hasCauseInstanceOf(AbortedStreamException.class);

        assertThat(callbackThread.get()).isNotSameAs(mainThread);
    }

    @Test
    void ignoresAfterTrailersIsWritten() {
        final HttpResponseWriter res = HttpResponse.streaming();
        res.write(ResponseHeaders.of(100));
        res.write(HttpHeaders.of(HttpHeaderNames.of("a"), "b"));
        res.write(ResponseHeaders.of(200));
        res.write(HttpHeaders.of(HttpHeaderNames.of("c"), "d")); // Split headers is trailers.

        // Ignored after trailers is written.
        res.write(HttpData.ofUtf8("foo"));
        res.write(HttpHeaders.of(HttpHeaderNames.of("e"), "f"));
        res.write(HttpHeaders.of(HttpHeaderNames.of("g"), "h"));
        res.close();

        final AggregatedHttpResponse aggregated = res.aggregate().join();
        // Informational headers
        assertThat(aggregated.informationals()).containsExactly(
                ResponseHeaders.of(HttpStatus.CONTINUE, HttpHeaderNames.of("a"), "b"));
        // Non-informational header
        assertThat(aggregated.headers()).isEqualTo(ResponseHeaders.of(200));

        assertThat(aggregated.contentUtf8()).isEmpty();
        assertThat(aggregated.trailers()).isEqualTo(HttpHeaders.of(HttpHeaderNames.of("c"), "d"));
    }

    private static class ParametersProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    arguments(true, true),
                    arguments(true, false),
                    arguments(false, true),
                    arguments(false, false));
        }
    }
}
