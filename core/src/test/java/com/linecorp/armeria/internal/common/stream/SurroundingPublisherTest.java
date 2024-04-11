/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common.stream;

import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SurroundingPublisherTest {

    @Test
    void zeroElementSurroundingPublisher() {
        // given
        final StreamMessage<Integer> zeroElement =
                new SurroundingPublisher<>(null, Mono.empty(), unused -> null);

        // when & then
        StepVerifier.create(zeroElement, 1)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(OneElementSurroundingPublisherProvider.class)
    void oneElementSurroundingPublisher_request1(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(OneElementSurroundingPublisherProvider.class)
    void oneElementSurroundingPublisher_requestAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher)
                    .expectNext(1)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(TwoElementsSurroundingPublisherProvider.class)
    void twoElementsSurroundingPublisher_request1(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(1)
                    .expectNext(2)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(TwoElementsSurroundingPublisherProvider.class)
    void twoElementsSurroundingPublisher_request1AndAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(Long.MAX_VALUE)
                    .expectNext(2)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(TwoElementsSurroundingPublisherProvider.class)
    void twoElementSurroundingPublisher_request2(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 2)
                    .expectNext(1, 2)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(TwoElementsSurroundingPublisherProvider.class)
    void twoElementSurroundingPublisher_requestAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher)
                    .expectNext(1, 2)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(ThreeElementsSurroundingPublisherProvider.class)
    void threeElementsSurroundingPublisher_request1(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(1)
                    .expectNext(2)
                    .thenRequest(1)
                    .expectNext(3)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(ThreeElementsSurroundingPublisherProvider.class)
    void threeElementsSurroundingPublisher_request1And2(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(2)
                    .expectNext(2, 3)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(ThreeElementsSurroundingPublisherProvider.class)
    void threeElementsSurroundingPublisher_request3(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 3)
                    .expectNext(1, 2, 3)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(ThreeElementsSurroundingPublisherProvider.class)
    void threeElementsSurroundingPublisher_request1AndAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(Long.MAX_VALUE)
                    .expectNext(2, 3)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(ThreeElementsSurroundingPublisherProvider.class)
    void threeElementsSurroundingPublisher_request2(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 2)
                    .expectNext(1, 2)
                    .thenRequest(1)
                    .expectNext(3)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(ThreeElementsSurroundingPublisherProvider.class)
    void threeElementsSurroundingPublisher_requestAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher)
                    .expectNext(1, 2, 3)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_request1(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(1)
                    .expectNext(2)
                    .thenRequest(1)
                    .expectNext(3)
                    .thenRequest(1)
                    .expectNext(4)
                    .thenRequest(1)
                    .expectNext(5)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_request1And3And1(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(3)
                    .expectNext(2, 3, 4)
                    .thenRequest(1)
                    .expectNext(5)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_request1AndAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 1)
                    .expectNext(1)
                    .thenRequest(Long.MAX_VALUE)
                    .expectNext(2, 3, 4, 5)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_request2And3(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 2)
                    .expectNext(1, 2)
                    .thenRequest(3)
                    .expectNext(3, 4, 5)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_request5(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 5)
                    .expectNext(1, 2, 3, 4, 5)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_requestAll(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher)
                    .expectNext(1, 2, 3, 4, 5)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsFinalizedSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_finalized100(StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 5)
                    .expectNext(1, 2, 3, 4, 100)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsFinalizedSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_finalized100_requestAll(
            StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher)
                    .expectNext(1, 2, 3, 4, 100)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsFinalizedSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_finalized100_request1BeforeAborted(
            StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 4)
                    .expectNext(1, 2, 3, 4)
                    .thenRequest(1)
                    .expectNext(100)
                    .expectComplete()
                    .verify();
    }

    @ParameterizedTest
    @ArgumentsSource(FiveElementsFinalizedSurroundingPublisherProvider.class)
    void fiveElementsSurroundingPublisher_finalized100_requestAllBeforeAborted(
            StreamMessage<Integer> surroundingPublisher) {
        // when & then
        StepVerifier.create(surroundingPublisher, 4)
                    .expectNext(1, 2, 3, 4)
                    .thenRequest(Integer.MAX_VALUE)
                    .expectNext(100)
                    .expectComplete()
                    .verify();
    }

    @Test
    void finalizer_aborted() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4, 5);
        final StreamMessage<Integer> aborted = streamMessage
                .peek(val -> {
                    if (val == 5) {
                        streamMessage.abort();
                    }
                });
        final Function<Throwable, Integer> finalizer = th -> {
            if (th instanceof AbortedStreamException) {
                return 100;
            }
            return -1;
        };
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, aborted, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4, 100)
                    .expectComplete()
                    .verify();
    }

    @Test
    void finalizer_thrown() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4, 5);
        final StreamMessage<Integer> aborted = streamMessage
                .peek(val -> {
                    if (val == 5) {
                        throw new RuntimeException();
                    }
                });
        final Function<Throwable, Integer> finalizer = th -> {
            if (th instanceof AbortedStreamException) {
                return 100;
            }
            return -1;
        };
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, aborted, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4, -1)
                    .expectComplete()
                    .verify();
    }

    @Test
    void finalizer_not_thrown() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4);
        final Function<Throwable, Integer> finalizer = th -> {
            if (th instanceof AbortedStreamException) {
                return 999;
            }
            if (th == null) {
                return 100;
            }
            return -1;
        };
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, streamMessage, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4, 100)
                    .expectComplete()
                    .verify();
    }

    @Test
    void finalizer_return_null() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4);
        final Function<Throwable, Integer> finalizer = unused -> null;
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, streamMessage, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4)
                    .expectComplete()
                    .verify();
    }

    @Test
    void finalizer_return_null_upstream_thrown() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4, 5);
        final StreamMessage<Integer> aborted = streamMessage
                .peek(val -> {
                    if (val == 5) {
                        throw new RuntimeException();
                    }
                });
        final Function<Throwable, Integer> finalizer = unused -> null;
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, aborted, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4)
                    .expectError(RuntimeException.class)
                    .verify();
    }

    @Test
    void finalizer_apply_thrown() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4);
        final Function<Throwable, Integer> finalizer = unused -> {
            throw new IllegalArgumentException();
        };
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, streamMessage, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4)
                    .expectError(IllegalArgumentException.class)
                    .verify();
    }

    @Test
    void finalizer_apply_thrown_upstream_thrown() {
        // given
        final StreamMessage<Integer> streamMessage = StreamMessage.of(2, 3, 4, 5);
        final StreamMessage<Integer> aborted = streamMessage
                .peek(val -> {
                    if (val == 5) {
                        throw new RuntimeException();
                    }
                });
        final Function<Throwable, Integer> finalizer = unused -> {
            throw new IllegalArgumentException();
        };
        final StreamMessage<Integer> publisher = new SurroundingPublisher<>(1, aborted, finalizer);

        // when & then
        StepVerifier.create(publisher)
                    .expectNext(1, 2, 3, 4)
                    .expectError(RuntimeException.class)
                    .verify();
    }

    private static class OneElementSurroundingPublisherProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(new SurroundingPublisher<>(null, Mono.empty(), unused -> 1),
                             new SurroundingPublisher<>(null, Mono.empty(), th -> 1))
                         .map(Arguments::of);
        }
    }

    private static class TwoElementsSurroundingPublisherProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(new SurroundingPublisher<>(1, Mono.empty(), unused -> 2),
                             new SurroundingPublisher<>(null, Mono.just(1), unused -> 2),
                             new SurroundingPublisher<>(1, Mono.empty(), th -> 2),
                             new SurroundingPublisher<>(null, Mono.just(1), th -> 2))
                         .map(Arguments::of);
        }
    }

    private static class ThreeElementsSurroundingPublisherProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(new SurroundingPublisher<>(1, Mono.just(2), unused -> 3),
                             new SurroundingPublisher<>(null, Flux.just(1, 2), unused -> 3),
                             new SurroundingPublisher<>(1, Mono.just(2), th -> 3),
                             new SurroundingPublisher<>(null, Flux.just(1, 2), th -> 3))
                         .map(Arguments::of);
        }
    }

    private static class FiveElementsSurroundingPublisherProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(new SurroundingPublisher<>(
                                     1, Flux.fromStream(IntStream.range(2, 5).boxed()), unused -> 5),
                             new SurroundingPublisher<>(
                                     null, Flux.fromStream(IntStream.range(1, 5).boxed()), unused -> 5),
                             new SurroundingPublisher<>(
                                     1, Flux.fromStream(IntStream.range(2, 5).boxed()), th -> 5),
                             new SurroundingPublisher<>(
                                     null, Flux.fromStream(IntStream.range(1, 5).boxed()), th -> 5))
                         .map(Arguments::of);
        }
    }

    private static class FiveElementsFinalizedSurroundingPublisherProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(new SurroundingPublisher<>(
                                     1,
                                     Flux.fromStream(IntStream.range(2, 10).boxed())
                                         .map(i -> {
                                             if (i >= 5) {
                                                 throw new RuntimeException();
                                             }
                                             return i;
                                         }),
                                     th -> {
                                         if (th instanceof RuntimeException) {
                                             return 100;
                                         }
                                         return -1;
                                     }),
                             new SurroundingPublisher<>(
                                     null,
                                     Flux.fromStream(IntStream.range(1, 10).boxed())
                                         .map(i -> {
                                             if (i >= 5) {
                                                 throw new RuntimeException();
                                             }
                                             return i;
                                         }),
                                     th -> {
                                         if (th instanceof RuntimeException) {
                                             return 100;
                                         }
                                         return -1;
                                     }))
                         .map(Arguments::of);
        }
    }
}
