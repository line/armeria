/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CancellationPropagationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService("/cancel", new CancellationService());
            sb.decorator(LoggingService.newDecorator());
            sb.requestTimeoutMillis(1000L);
        }
    };

    static final AtomicInteger singleValuePublisherCancelCallCounter = new AtomicInteger();
    static final AtomicInteger multiValuePublisherCancelCallCounter = new AtomicInteger();
    static final AtomicInteger futureCancelCallCounter = new AtomicInteger();

    @BeforeEach
    void setUp() {
        singleValuePublisherCancelCallCounter.set(0);
        multiValuePublisherCancelCallCounter.set(0);
        futureCancelCallCounter.set(0);
    }

    @Test
    void testCancellationPropagation() {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res;

        res = client.get("/cancel/single-value-pub").aggregate().join();
        validateCancellation(res, singleValuePublisherCancelCallCounter);
        res = client.get("/cancel/multi-value-pub").aggregate().join();
        validateCancellation(res, multiValuePublisherCancelCallCounter);
        res = client.get("/cancel/future").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // Should not cancel CompletableFuture. A HttpResponse could be leaked if it is set after completion.
        assertThat(futureCancelCallCounter).hasValue(0);

        resetCancelCallCounters(); // Reset cancellation call counter.

        res = client.get("/cancel/single-value-pub-json").aggregate().join();
        validateCancellation(res, singleValuePublisherCancelCallCounter);
        res = client.get("/cancel/multi-value-pub-json").aggregate().join();
        validateCancellation(res, multiValuePublisherCancelCallCounter);

        resetCancelCallCounters();

        res = client.get("/cancel/multi-value-pub-json-seq").aggregate().join();
        validateCancellation(res, multiValuePublisherCancelCallCounter);
    }

    static void validateCancellation(AggregatedHttpResponse res, AtomicInteger cancelCallCounter) {
        await().untilAsserted(() -> assertThat(cancelCallCounter.get()).isOne());
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    static void resetCancelCallCounters() {
        singleValuePublisherCancelCallCounter.set(0);
        multiValuePublisherCancelCallCounter.set(0);
        futureCancelCallCounter.set(0);
    }

    private static class CancellationService {

        private static final Mono<String> delayedMonoRes =
                Mono.just("hello, world!")
                    .delayElement(Duration.ofSeconds(5L))
                    .doOnCancel(singleValuePublisherCancelCallCounter::incrementAndGet);

        private static final Flux<String> delayedFluxRes =
                Flux.just("hello", "world")
                    .delaySubscription(Duration.ofSeconds(5L))
                    .doOnCancel(multiValuePublisherCancelCallCounter::incrementAndGet);

        @Get("/single-value-pub")
        public Mono<String> singleValuePub() {
            return delayedMonoRes;
        }

        @Get("/single-value-pub-json")
        @ProducesJson
        public Mono<String> singleValuePubJson() {
            return delayedMonoRes;
        }

        @Get("/multi-value-pub")
        public Flux<String> multiValuePub() {
            return delayedFluxRes;
        }

        @Get("/multi-value-pub-json")
        @ProducesJson
        public Flux<String> multiValuePubJson() {
            return delayedFluxRes;
        }

        @Get("/multi-value-pub-json-seq")
        @ProducesJsonSequences
        public Flux<String> multiValuePubJsonSeq() {
            return delayedFluxRes;
        }

        @Get("/future")
        public CompletableFuture<String> future() {
            final CompletableFuture<String> future = new CompletableFuture<>();
            future.whenComplete((ignored, cause) -> futureCancelCallCounter.incrementAndGet());
            return future;
        }
    }
}
