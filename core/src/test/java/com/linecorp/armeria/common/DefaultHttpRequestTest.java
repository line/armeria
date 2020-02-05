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

import io.netty.buffer.PooledByteBufAllocator;

class DefaultHttpRequestTest {

    /**
     * The aggregation future must be completed even if the request being aggregated has been aborted.
     */
    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void abortedAggregation(boolean executorSpecified, boolean withPooledObjects, Throwable abortCause) {
        final Thread mainThread = Thread.currentThread();
        final DefaultHttpRequest req = new DefaultHttpRequest(RequestHeaders.of(HttpMethod.GET, "/foo"));
        final CompletableFuture<AggregatedHttpRequest> future;

        // Practically same execution, but we need to test the both case due to code duplication.
        if (executorSpecified) {
            if (withPooledObjects) {
                future = req.aggregateWithPooledObjects(
                        CommonPools.workerGroup().next(), PooledByteBufAllocator.DEFAULT);
            } else {
                future = req.aggregate(CommonPools.workerGroup().next());
            }
        } else {
            if (withPooledObjects) {
                future = req.aggregateWithPooledObjects(PooledByteBufAllocator.DEFAULT);
            } else {
                future = req.aggregate();
            }
        }

        final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        if (abortCause == null) {
            assertThatThrownBy(() -> {
                final CompletableFuture<AggregatedHttpRequest> f =
                        future.whenComplete((unused, cause) -> callbackThread.set(Thread.currentThread()));
                req.abort();
                f.join();
            }).hasCauseInstanceOf(AbortedStreamException.class);
        } else {
            assertThatThrownBy(() -> {
                final CompletableFuture<AggregatedHttpRequest> f =
                        future.whenComplete((unused, cause) -> callbackThread.set(Thread.currentThread()));
                req.abort(abortCause);
                f.join();
            }).hasCauseInstanceOf(abortCause.getClass());
        }

        assertThat(callbackThread.get()).isNotSameAs(mainThread);
    }

    @Test
    void ignoresAfterTrailersIsWritten() {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.GET, "/foo");
        req.write(HttpData.ofUtf8("foo"));
        req.write(HttpHeaders.of(HttpHeaderNames.of("a"), "b"));
        req.write(HttpHeaders.of(HttpHeaderNames.of("c"), "d")); // Ignored.
        req.close();

        final AggregatedHttpRequest aggregated = req.aggregate().join();
        // Request headers
        assertThat(aggregated.headers()).isEqualTo(
                RequestHeaders.of(HttpMethod.GET, "/foo",
                                  HttpHeaderNames.CONTENT_LENGTH, "3"));
        // Content
        assertThat(aggregated.contentUtf8()).isEqualTo("foo");
        // Trailers
        assertThat(aggregated.trailers()).isEqualTo(HttpHeaders.of(HttpHeaderNames.of("a"), "b"));
    }

    private static class ParametersProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    arguments(true, true, null),
                    arguments(true, false, new IllegalStateException("abort stream with a specified cause")),
                    arguments(false, true, new IllegalStateException("abort stream with a specified cause")),
                    arguments(false, false, null));
        }
    }
}
