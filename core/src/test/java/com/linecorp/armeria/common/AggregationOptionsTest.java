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

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.FilteredStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBufAllocator;
import reactor.core.publisher.Flux;

class AggregationOptionsTest {

    @ArgumentsSource(StreamMessageProvider.class)
    @ParameterizedTest
    void streamMessage_cached(StreamMessage<Integer> stream) {
        final AtomicInteger counter = new AtomicInteger();
        final Function<List<Integer>, Integer> aggregator = nums -> {
            counter.incrementAndGet();
            return nums.stream().reduce(0, Integer::sum);
        };
        final AggregationOptions<Integer, Integer> options = AggregationOptions.builder(aggregator)
                                                                               .cacheResult(true)
                                                                               .build();
        final int sumFirst = stream.aggregate(options).join();
        assertThat(sumFirst).isEqualTo(10);
        assertThat(counter).hasValue(1);

        final int sumSecond = stream.aggregate(options).join();
        assertThat(sumSecond).isEqualTo(10);
        // Make sure that the aggregation function is not evaluated.
        assertThat(counter).hasValue(1);
    }

    @ArgumentsSource(StreamMessageProvider.class)
    @ParameterizedTest
    void streamMessage_nonCached(StreamMessage<Integer> stream) {
        final AggregationOptions<Integer, Integer> options =
                AggregationOptions.<Integer, Integer>builder(nums -> {
                                      return nums.stream().reduce(0, Integer::sum);
                                  }).cacheResult(false)
                                  .build();
        final int sum = stream.aggregate(options).join();
        assertThat(sum).isEqualTo(10);

        // Disallow the second aggregation
        assertThatThrownBy(() -> stream.aggregate(options).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void disallowPooledObjectWithCache() {
        assertThatThrownBy(() -> {
            AggregationOptions.<Integer, Integer>builder(nums -> 1)
                              .alloc(ByteBufAllocator.DEFAULT)
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
        assertThat(agg1).isSameAs(agg0);
    }

    @ArgumentsSource(HttpRequestProvider.class)
    @ParameterizedTest
    void httpRequest_notCached_withPooledObjects(HttpRequest request) {
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final AggregatedHttpRequest agg0 = request.aggregateWithPooledObjects(alloc).join();
        assertThat(agg0.method()).isEqualTo(HttpMethod.GET);
        assertThat(agg0.path()).isEqualTo("/abc");
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        agg0.content().close();

        assertThatThrownBy(() -> request.aggregateWithPooledObjects(alloc).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> request.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
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
        final ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
        final AggregatedHttpResponse agg0 = response.aggregateWithPooledObjects(alloc).join();
        assertThat(agg0.status()).isEqualTo(HttpStatus.OK);
        assertThat(agg0.content().toStringUtf8()).isEqualTo("12");
        agg0.content().close();

        assertThatThrownBy(() -> response.aggregateWithPooledObjects(alloc).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> response.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private static class StreamMessageProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final StreamMessage<Integer> fixed = StreamMessage.of(1, 2, 3, 4);
            final DefaultStreamMessage<Integer> defaultStream = new DefaultStreamMessage<>();
            defaultStream.write(1);
            defaultStream.write(2);
            defaultStream.write(3);
            defaultStream.write(4);
            defaultStream.close();
            final StreamMessage<Integer> publisherBased = StreamMessage.of(Flux.range(1, 4));
            final StreamMessage<Integer> fuseable = StreamMessage.of(0, 1, 2, 3).map(x -> x + 1);
            final StreamMessage<Integer> recoverable =
                    StreamMessage.<Integer>aborted(new AnticipatedException())
                                 .recoverAndResume(cause -> StreamMessage.of(1, 2, 3, 4));
            final FilteredStreamMessage<Integer, Integer> filtered =
                    new FilteredStreamMessage<Integer, Integer>(StreamMessage.of(0, 1, 2, 3)) {
                        @Override
                        protected Integer filter(Integer obj) {
                            return obj + 1;
                        }
                    };

            return Stream.of(fixed, defaultStream, publisherBased, fuseable, recoverable, filtered)
                         .map(Arguments::of);
        }
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
            return Stream.of(fixed, streaming, publisherBased, fuseable, filtered)
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
            return Stream.of(fixed, streaming, publisherBased, fuseable, filtered)
                         .map(Arguments::of);
        }
    }
}
