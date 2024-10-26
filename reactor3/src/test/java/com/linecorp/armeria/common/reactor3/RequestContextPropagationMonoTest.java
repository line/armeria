/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.common.reactor3;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.micrometer.context.RequestContextThreadLocalAccessor;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;

@GenerateNativeImageTrace
class RequestContextPropagationMonoTest {

    @BeforeAll
    static void setUp() {
        ContextRegistry
                .getInstance()
                .registerThreadLocalAccessor(new RequestContextThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void tearDown() {
        Hooks.disableAutomaticContextPropagation();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoCreate_success(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        mono = addCallbacks(Mono.create(sink -> {
            assertThat(ctxExists(ctx)).isTrue();
            sink.success("foo");
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoCreate_error(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        mono = addCallbacks(Mono.create(sink -> {
            assertThat(ctxExists(ctx)).isTrue();
            sink.error(new AnticipatedException());
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(mono)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoCreate_currentContext(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        mono = addCallbacks(Mono.create(sink -> {
            assertThat(ctxExists(ctx)).isTrue();
            sink.success("foo");
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoDefer(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        mono = addCallbacks(Mono.defer(() -> Mono.fromSupplier(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return "foo";
        })).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoFromPublisher(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        mono = addCallbacks(Mono.from(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.onSubscribe(noopSubscription());
            s.onNext("foo");
            s.onComplete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoError(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        mono = addCallbacks(Mono.error(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return new AnticipatedException();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(mono)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoFirst(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        mono = addCallbacks(Mono.firstWithSignal(Mono.delay(Duration.ofMillis(1000)).then(Mono.just("bar")),
                                                 Mono.fromCallable(() -> {
                                                     assertThat(ctxExists(ctx)).isTrue();
                                                     return "foo";
                                                 }))
                                .publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoFromFuture(boolean useContextCapture) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("foo");
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        mono = addCallbacks(Mono.fromFuture(future)
                                .publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoDelay(boolean useContextCapture) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("foo");
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        mono = addCallbacks(Mono.delay(Duration.ofMillis(100)).then(Mono.fromCallable(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return "foo";
        })).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void monoZip(boolean useContextCapture) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("foo");
        final ClientRequestContext ctx = newContext();
        final Mono<Tuple2<String, String>> mono;
            mono = addCallbacks(Mono.zip(Mono.fromSupplier(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "foo";
            }), Mono.fromSupplier(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "bar";
            })).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(mono)
                            .expectNextMatches(t -> "foo".equals(t.getT1()) && "bar".equals(t.getT2()))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(mono)
                        .expectNextMatches(t -> "foo".equals(t.getT1()) && "bar".equals(t.getT2()))
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @Test
    void subscriberContextIsNotMissing() {
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        mono = Mono.deferContextual(Mono::just).handle((reactorCtx, sink) -> {
            assertThat((String) reactorCtx.get("foo")).isEqualTo("bar");
            sink.next("baz");
        });

        final Mono<String> mono1 = mono.contextWrite(reactorCtx -> reactorCtx.put("foo", "bar"));
        StepVerifier.create(mono1)
                    .expectNextMatches("baz"::equals)
                    .verifyComplete();
        assertThat(ctxExists(ctx)).isFalse();
    }

    @Test
    void ctxShouldBeCleanUpEvenIfErrorOccursDuringReactorOperationOnSchedulerThread()
            throws InterruptedException {
        // Given
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        final Scheduler single = Schedulers.single();

        // When
        mono = Mono.just("Hello")
                   .subscribeOn(single)
                   .delayElement(Duration.ofMillis(1000))
                   .map(s -> {
                       if ("Hello".equals(s)) {
                           throw new RuntimeException();
                       }
                       return s;
                   })
                   .contextWrite(Context.of(RequestContext.class, ctx));

        // Then
        StepVerifier.create(mono)
                    .expectError(RuntimeException.class)
                    .verify();

        final CountDownLatch latch = new CountDownLatch(1);
        single.schedule(() -> {
            assertThat(ctxExists(ctx)).isFalse();
            latch.countDown();
        });
        latch.await();

        assertThat(ctxExists(ctx)).isFalse();
    }

    static Subscription noopSubscription() {
        return new Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {}
        };
    }

    static boolean ctxExists(ClientRequestContext ctx) {
        return RequestContext.currentOrNull() == ctx;
    }

    static ClientRequestContext newContext() {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .build();
    }

    private static <T> Mono<T> addCallbacks(Mono<T> mono0, ClientRequestContext ctx,
                                            boolean useContextCapture) {
        final Mono<T> mono = mono0.doFirst(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnSubscribe(s -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnRequest(l -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnNext(foo -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnSuccess(t -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnEach(s -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnError(t -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnCancel(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doFinally(t -> assertThat(ctxExists(ctx)).isTrue())
                                  .doAfterTerminate(() -> assertThat(ctxExists(ctx)).isTrue());
        if (useContextCapture) {
            return mono.contextCapture();
        }
        return mono.contextWrite(Context.of(RequestContext.class, ctx));
    }
}
