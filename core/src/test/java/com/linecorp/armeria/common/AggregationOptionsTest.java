/*
 * Copyright 2022 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.HeaderOverridingHttpRequest;

import io.netty.buffer.ByteBufAllocator;
import reactor.core.publisher.Flux;

class AggregationOptionsTest {

    private static final Logger logger = LoggerFactory.getLogger(AggregationOptionsTest.class);

    @Test
    void disallowPooledObjectWithCache() {
        assertThatThrownBy(() -> {
            AggregationOptions.builder()
                              .usePooledObjects(ByteBufAllocator.DEFAULT)
                              .cacheResult(true)
                              .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Can't cache pooled objects");
    }

    @ArgumentsSource(HttpRequestProvider.class)
    @ParameterizedTest
    void httpRequest_cached(HttpRequest request) {
        final AggregatedHttpRequest agg0 = request.aggregate().join();
        assertThat(agg0.method()).isEqualTo(HttpMethod.GET);
        assertThat(agg0.path()).isEqualTo("/abc");
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        final AggregatedHttpRequest agg1 = request.aggregate().join();
        if (request instanceof HeaderOverridingHttpRequest) {
            // A new object is created for the overridden header .
            assertThat(agg1).isEqualTo(agg0);
            assertThat(agg0.content()).isSameAs(agg1.content());
        } else {
            assertThat(agg1).isSameAs(agg0);
        }
    }

    @ArgumentsSource(HttpRequestProvider.class)
    @ParameterizedTest
    void httpRequest_notCached_withPooledObjects(HttpRequest request) {
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final AggregatedHttpRequest agg0 = request.aggregate(AggregationOptions.usePooledObjects(alloc)).join();
        assertThat(agg0.method()).isEqualTo(HttpMethod.GET);
        assertThat(agg0.path()).isEqualTo("/abc");
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        agg0.content().close();

        assertThatThrownBy(() -> request.aggregate(AggregationOptions.usePooledObjects(alloc)).join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the stream was aggregated");

        assertThatThrownBy(() -> request.aggregate().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the stream was aggregated");
    }

    @ArgumentsSource(HttpRequestProvider.class)
    @ParameterizedTest
    void httpRequest_cached_followingWithPooledObjects(HttpRequest request) {
        final AggregatedHttpRequest agg0 = request.aggregate().join();
        assertThat(agg0.method()).isEqualTo(HttpMethod.GET);
        assertThat(agg0.path()).isEqualTo("/abc");
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final AggregatedHttpRequest agg1 = request.aggregate(AggregationOptions.usePooledObjects(alloc)).join();
        if (request instanceof HeaderOverridingHttpRequest) {
            // A new object is created for the overridden header .
            assertThat(agg1).isEqualTo(agg0);
            assertThat(agg0.content()).isSameAs(agg1.content());
        } else {
            assertThat(agg1).isSameAs(agg0);
        }
    }

    @ArgumentsSource(HttpRequestProvider.class)
    @ParameterizedTest
    void httpRequest_cached_exceptionWhenNoPreferCached(HttpRequest request) {
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final AggregatedHttpRequest agg0 =
                request.aggregate().join();
        assertThat(agg0.method()).isEqualTo(HttpMethod.GET);
        assertThat(agg0.path()).isEqualTo("/abc");
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");

        assertThatThrownBy(() -> request.aggregate(AggregationOptions.builder()
                                                                     .usePooledObjects(alloc, false)
                                                                     .build())
                                        .join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the stream was aggregated");
    }

    @ArgumentsSource(HttpResponseProvider.class)
    @ParameterizedTest
    void httpResponse_cached(HttpResponse response) {
        final AggregatedHttpResponse agg0 = response.aggregate().join();
        assertThat(agg0.status()).isEqualTo(HttpStatus.OK);
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        final AggregatedHttpResponse agg1 = response.aggregate().join();
        assertThat(agg1).isSameAs(agg0);
    }

    @ArgumentsSource(HttpResponseProvider.class)
    @ParameterizedTest
    void httpResponse_notCached_withPooledObjects(HttpResponse response) {
        final AggregatedHttpResponse agg0 = response.aggregate(AggregationOptions.builder()
                                                                                 .usePooledObjects()
                                                                                 .build())
                                                    .join();
        assertThat(agg0.status()).isEqualTo(HttpStatus.OK);
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        agg0.content().close();

        assertThatThrownBy(() -> response.aggregate(AggregationOptions.builder()
                                                                      .usePooledObjects()
                                                                      .build())
                                         .join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the stream was aggregated");

        assertThatThrownBy(() -> response.aggregate().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the stream was aggregated");
    }

    @ArgumentsSource(HttpResponseProvider.class)
    @ParameterizedTest
    void httpResponse_cached_followingWithPooledObjects(HttpResponse response) {
        final AggregatedHttpResponse agg0 = response.aggregate().join();
        assertThat(agg0.status()).isEqualTo(HttpStatus.OK);
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        final AggregatedHttpResponse agg1 =
                response.aggregate(AggregationOptions.builder().usePooledObjects().build())
                        .join();
        assertThat(agg1).isSameAs(agg0);
    }

    @ArgumentsSource(HttpResponseProvider.class)
    @ParameterizedTest
    void httpResponse_cached_exceptionWhenNoPreferCached(HttpResponse response) {
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final AggregatedHttpResponse agg0 =
                response.aggregate().join();
        assertThat(agg0.status()).isEqualTo(HttpStatus.OK);
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");

        assertThatThrownBy(() -> response.aggregate(AggregationOptions.builder()
                                                                      .usePooledObjects(alloc, false)
                                                                      .build())
                                         .join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the stream was aggregated");
    }

    @Test
    void testConcurrentAggregation() throws InterruptedException {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"), HttpData.ofUtf8("1"),
                                                   HttpData.ofUtf8("2"),
                                                   HttpData.ofUtf8("3"), HttpData.ofUtf8("4"));
        final int concurrency = 20;
        final CountDownLatch startLatch = new CountDownLatch(concurrency);
        final AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            CommonPools.blockingTaskExecutor().submit(() -> {
                try {
                    startLatch.countDown();
                    startLatch.await();
                } catch (InterruptedException e) {
                    logger.warn("interrupted: ", e);
                }
                final AggregatedHttpRequest agg = request.aggregate().join();
                assertThat(agg.contentUtf8()).isEqualTo("1234");
                success.incrementAndGet();
            });
        }

        await().untilAtomic(success, Matchers.equalTo(concurrency));
    }

    private static class HttpRequestProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/abc");
            final HttpRequest fixed = HttpRequest.of(headers, HttpData.ofUtf8("1"), HttpData.ofUtf8("2"));
            final HttpRequestWriter streaming = HttpRequest.streaming(headers);
            streaming.write(HttpData.ofUtf8("1"));
            streaming.write(HttpData.ofUtf8("2"));
            streaming.close();

            final HttpRequest publisherBased =
                    HttpRequest.of(headers, Flux.fromIterable(
                            ImmutableList.of(HttpData.ofUtf8("1"), HttpData.ofUtf8("2"))));
            final HttpRequest fuseable = HttpRequest.of(headers, HttpData.ofUtf8("0"), HttpData.ofUtf8("1"))
                                                    .mapData(data -> HttpData.ofUtf8(Integer.toString(
                                                            Integer.parseInt(data.toStringUtf8()) + 1)));

            final HttpRequest filtered =
                    new FilteredHttpRequest(
                            HttpRequest.of(headers, HttpData.ofUtf8("0"), HttpData.ofUtf8("1"))) {

                        @Override
                        protected HttpObject filter(HttpObject obj) {
                            final HttpData data = (HttpData) obj;
                            return HttpData.ofUtf8(
                                    Integer.toString(Integer.parseInt(data.toStringUtf8()) + 1));
                        }
                    };

            final HttpRequest streamMessageBased =
                    HttpRequest.of(headers, StreamMessage.of(HttpData.ofUtf8("1"), HttpData.ofUtf8("2")));

            final HttpRequest headerOverriding =
                    HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"), HttpData.ofUtf8("1"),
                                   HttpData.ofUtf8("2"))
                               .withHeaders(headers);

            return Stream.of(fixed, streaming, publisherBased, fuseable, filtered,
                             streamMessageBased, headerOverriding)
                         .map(Arguments::of);
        }
    }

    private static class HttpResponseProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
            final HttpResponse fixed = HttpResponse.of(headers, HttpData.ofUtf8("1"), HttpData.ofUtf8("2"));
            final HttpResponseWriter streaming = HttpResponse.streaming();
            streaming.write(headers);
            streaming.write(HttpData.ofUtf8("1"));
            streaming.write(HttpData.ofUtf8("2"));
            streaming.close();

            final HttpResponse publisherBased =
                    HttpResponse.of(headers, Flux.fromIterable(
                            ImmutableList.of(HttpData.ofUtf8("1"), HttpData.ofUtf8("2"))));
            final HttpResponse fuseable = HttpResponse.of(headers, HttpData.ofUtf8("0"), HttpData.ofUtf8("1"))
                                                      .mapData(data -> HttpData.ofUtf8(Integer.toString(
                                                              Integer.parseInt(data.toStringUtf8()) + 1)));

            final HttpResponse filtered =
                    new FilteredHttpResponse(
                            HttpResponse.of(headers, HttpData.ofUtf8("0"), HttpData.ofUtf8("1"))) {

                        @Override
                        protected HttpObject filter(HttpObject obj) {
                            if (!(obj instanceof HttpData)) {
                                return obj;
                            }
                            final HttpData data = (HttpData) obj;
                            return HttpData.ofUtf8(
                                    Integer.toString(Integer.parseInt(data.toStringUtf8()) + 1));
                        }
                    };

            final HttpResponse streamMessageBased =
                    HttpResponse.of(headers, StreamMessage.of(HttpData.ofUtf8("1"), HttpData.ofUtf8("2")));

            final HttpResponse headerOverriding =
                    HttpResponse.of(ResponseHeaders.of(HttpStatus.BAD_REQUEST),
                                    HttpData.ofUtf8("1"), HttpData.ofUtf8("2"))
                                .mapHeaders(headers0 -> headers);
            return Stream.of(fixed, streaming, publisherBased, fuseable, filtered,
                             streamMessageBased, headerOverriding)
                         .map(Arguments::of);
        }
    }
}
