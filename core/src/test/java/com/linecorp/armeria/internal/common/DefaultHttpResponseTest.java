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
package com.linecorp.armeria.internal.common;

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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.test.StepVerifier;

class DefaultHttpResponseTest {

    @Test
    void closeWithAggregatedResponse() {
        // Always headers only.
        final HttpResponseWriter res1 = HttpResponse.streaming();
        res1.close(AggregatedHttpResponse.of(204));
        StepVerifier.create(res1)
                    .expectNext(ResponseHeaders.of(204))
                    .expectComplete()
                    .verify();

        // Headers only.
        final HttpResponseWriter res2 = HttpResponse.streaming();
        res2.close(AggregatedHttpResponse.of(ResponseHeaders.of(200)));
        StepVerifier.create(res2)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 0))
                    .expectComplete()
                    .verify();

        // Headers and body.
        final HttpResponseWriter res3 = HttpResponse.streaming();
        res3.close(AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.ofUtf8("foo")));
        StepVerifier.create(res3)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3))
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectComplete()
                    .verify();

        // Headers, body and trailers.
        final HttpResponseWriter res4 = HttpResponse.streaming();
        res4.close(AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.ofUtf8("bar"),
                                             HttpHeaders.of("x-trailer", true)));
        StepVerifier.create(res4)
                    .expectNext(ResponseHeaders.of(200))
                    .expectNext(HttpData.ofUtf8("bar"))
                    .expectNext(HttpHeaders.of("x-trailer", true))
                    .expectComplete()
                    .verify();
    }

    @Test
    void closeMustReleaseAggregatedContent() {
        final HttpResponseWriter res = HttpResponse.streaming();
        final ByteBuf buf = Unpooled.directBuffer();
        buf.writeCharSequence("foo", StandardCharsets.UTF_8);
        final HttpData data = HttpData.wrap(buf).withEndOfStream();
        res.close();
        res.close(AggregatedHttpResponse.of(ResponseHeaders.of(200), data));
        assertThat(buf.refCnt()).isZero();
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
                future = res.aggregate(AggregationOptions.builder()
                                                         .usePooledObjects()
                                                         .executor(CommonPools.workerGroup().next())
                                                         .build());
            } else {
                future = res.aggregate(CommonPools.workerGroup().next());
            }
        } else {
            if (withPooledObjects) {
                future = res.aggregate(AggregationOptions.builder().usePooledObjects().build());
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
