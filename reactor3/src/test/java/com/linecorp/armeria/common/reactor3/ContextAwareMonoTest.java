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
package com.linecorp.armeria.common.reactor3;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.reactor3.RequestContextHooks.ContextAwareMono;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

@GenerateNativeImageTrace
class ContextAwareMonoTest {

    @BeforeAll
    static void setUp() {
        RequestContextHooks.enable();
    }

    @AfterAll
    static void tearDown() {
        RequestContextHooks.disable();
    }

    @Test
    void monoJust() {
        // MonoJust is a scalar type and could be subscribed by multiple requests.
        // Therefore, Mono.just(...), Mono.empty() and Mono.error(ex) should not return a ContextAwareMono.
        final Mono<String> mono = Mono.just("foo");
        final Mono<String> empty = Mono.empty();
        final Mono<String> error = Mono.error(new IllegalStateException("boom"));
        assertThat(mono).isNotExactlyInstanceOf(ContextAwareMono.class);
        assertThat(empty).isNotExactlyInstanceOf(ContextAwareMono.class);
        assertThat(error).isNotExactlyInstanceOf(ContextAwareMono.class);
    }

    @Test
    void monoCreate_success() {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.create(sink -> {
                assertThat(ctxExists(ctx)).isTrue();
                sink.success("foo");
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoCreate_error() {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.create(sink -> {
                assertThat(ctxExists(ctx)).isTrue();
                sink.error(new AnticipatedException());
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .verifyErrorMatches(t -> ctxExists(ctx) && t instanceof AnticipatedException);
    }

    @Test
    void monoCreate_currentContext() {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.create(sink -> {
                assertThat(ctxExists(ctx)).isTrue();
                sink.success("foo");
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoDefer() {
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.defer(() -> Mono.fromSupplier(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "foo";
            })).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoFromPublisher() {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.from(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext("foo");
                s.onComplete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoError() {
        final ClientRequestContext ctx = newContext();
        final Mono<Object> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.error(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return new AnticipatedException();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .verifyErrorMatches(t -> ctxExists(ctx) && t instanceof AnticipatedException);
    }

    @Test
    void monoFirst() {
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.firstWithSignal(Mono.delay(Duration.ofMillis(1000)).then(Mono.just("bar")),
                                                     Mono.fromCallable(() -> {
                                                         assertThat(ctxExists(ctx)).isTrue();
                                                         return "foo";
                                                     })).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoFromFuture() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("foo");
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.fromFuture(future).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoDelay() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("foo");
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.delay(Duration.ofMillis(100)).then(Mono.fromCallable(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "foo";
            })).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void monoZip() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("foo");
        final ClientRequestContext ctx = newContext();
        final Mono<Tuple2<String, String>> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = addCallbacks(Mono.zip(Mono.fromSupplier(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "foo";
            }), Mono.fromSupplier(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "bar";
            })).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(mono)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(t -> ctxExists(ctx) &&
                                            "foo".equals(t.getT1()) && "bar".equals(t.getT2()))
                    .verifyComplete();
    }

    @Test
    void subscriberContextIsNotMissing() {
        final ClientRequestContext ctx = newContext();
        final Mono<String> mono;
        try (SafeCloseable ignored = ctx.push()) {
            mono = Mono.deferContextual(Mono::just).handle((reactorCtx, sink) -> {
                assertThat((String) reactorCtx.get("foo")).isEqualTo("bar");
                sink.next("baz");
            });
        }
        final Mono<String> mono1 = mono.contextWrite(reactorCtx -> reactorCtx.put("foo", "bar"));
        StepVerifier.create(mono1)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "baz".equals(s))
                    .verifyComplete();
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

    private static <T> Mono<T> addCallbacks(Mono<T> mono, ClientRequestContext ctx) {
        return mono.doFirst(() -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnSubscribe(s -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnRequest(l -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnNext(foo -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnSuccess(t -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnEach(s -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnError(t -> assertThat(ctxExists(ctx)).isTrue())
                   .doAfterTerminate(() -> assertThat(ctxExists(ctx)).isTrue());
        // doOnCancel and doFinally do not have context because we cannot add a hook to the cancel.
    }
}
