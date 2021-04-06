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

import static com.linecorp.armeria.common.reactor3.ContextAwareMonoTest.ctxExists;
import static com.linecorp.armeria.common.reactor3.ContextAwareMonoTest.newContext;
import static com.linecorp.armeria.common.reactor3.ContextAwareMonoTest.noopSubscription;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.reactor3.RequestContextHooks.ContextAwareMono;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ContextAwareFluxTest {

    @BeforeAll
    static void setUp() {
        RequestContextHooks.enable();
    }

    @AfterAll
    static void tearDown() {
        RequestContextHooks.disable();
    }

    @Test
    void fluxJust() {
        // FluxJust and FluxEmpty are a scalar type and could be subscribed by multiple requests.
        // Therefore, Flux.just(...) and Flux.empty() should not return a ContextAwareFlux.
        final Flux<String> just = Flux.just("foo");
        final Flux<String> empty = Flux.empty();
        assertThat(just).isNotExactlyInstanceOf(ContextAwareMono.class);
        assertThat(empty).isNotExactlyInstanceOf(ContextAwareMono.class);
    }

    @Test
    void fluxError() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.error(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return new AnticipatedException();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .verifyErrorMatches(t -> ctxExists(ctx) && t instanceof AnticipatedException);
    }

    @Test
    void fluxFromPublisher() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.from(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext("foo");
                s.onComplete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxCreate() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.create(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.next("foo");
                s.complete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxCreate_error() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.create(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.error(new AnticipatedException());
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .verifyErrorMatches(t -> ctxExists(ctx) && t instanceof AnticipatedException);
    }

    @Test
    void fluxConcat() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.concat(Mono.fromSupplier(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "foo";
            }), Mono.fromCallable(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return "bar";
            })).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .expectNextMatches(s -> ctxExists(ctx) && "bar".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxDefer() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.defer(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return Flux.just("foo");
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxFromStream() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.fromStream(() -> {
                assertThat(ctxExists(ctx)).isTrue();
                return Stream.of("foo");
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxCombineLatest() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.combineLatest(Mono.just("foo"), Mono.just("bar"), (a, b) -> {
                assertThat(ctxExists(ctx)).isTrue();
                return a;
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxGenerate() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.generate(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.next("foo");
                s.complete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxMerge() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.mergeSequential(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext("foo");
                s.onComplete();
            }, s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext("bar");
                s.onComplete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .expectNextMatches(s -> ctxExists(ctx) && "bar".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxPush() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.push(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.next("foo");
                s.complete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxSwitchOnNext() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.switchOnNext(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext((Publisher<String>) s1 -> {
                    assertThat(ctxExists(ctx)).isTrue();
                    s1.onSubscribe(noopSubscription());
                    s1.onNext("foo");
                    s1.onComplete();
                });
                s.onComplete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxZip() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.zip(Mono.just("foo"), Mono.just("bar"), (foo, bar) -> {
                assertThat(ctxExists(ctx)).isTrue();
                return foo;
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxInterval() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.interval(Duration.ofMillis(100)).take(2).concatMap(a -> {
                assertThat(ctxExists(ctx)).isTrue();
                return Mono.just("foo");
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void fluxConcatDelayError() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.concatDelayError(s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext("foo");
                s.onError(new AnticipatedException());
            }, s -> {
                s.onSubscribe(noopSubscription());
                s.onNext("bar");
                s.onComplete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .expectNextMatches(s -> ctxExists(ctx) && "bar".equals(s))
                    .verifyErrorMatches(t -> ctxExists(ctx) && t instanceof AnticipatedException);
    }

    @Test
    void fluxTransform() {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = addCallbacks(Flux.just("foo").transform(fooFlux -> s -> {
                assertThat(ctxExists(ctx)).isTrue();
                s.onSubscribe(noopSubscription());
                s.onNext(fooFlux.blockFirst());
                s.onComplete();
            }).publishOn(Schedulers.single()), ctx);
        }
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void connectableFlux() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            final ConnectableFlux<String> connectableFlux = Flux.just("foo").publish();
            flux = addCallbacks(connectableFlux.autoConnect(2).publishOn(Schedulers.single()), ctx);
        }
        flux.subscribe().dispose();
        StepVerifier.create(flux)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "foo".equals(s))
                    .verifyComplete();
    }

    @Test
    void connectableFlux_dispose() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        final CompletableFuture<Disposable> future = new CompletableFuture<>();
        try (SafeCloseable ignored = ctx.push()) {
            final ConnectableFlux<String> connectableFlux = Flux.just("foo").publish();
            flux = addCallbacks(connectableFlux.autoConnect(2, disposable -> {
                assertThat(ctxExists(ctx)).isTrue();
                future.complete(disposable);
            }).publishOn(Schedulers.single()), ctx);
        }
        flux.subscribe().dispose();
        flux.subscribe().dispose();
        assertThat(future.join().isDisposed()).isTrue();
    }

    @Test
    void subscriberContextIsNotMissing() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        try (SafeCloseable ignored = ctx.push()) {
            flux = Flux.deferWithContext(reactorCtx -> {
                assertThat((String) reactorCtx.get("foo")).isEqualTo("bar");
                return Flux.just("baz");
            });
        }
        final Flux<String> flux1 = flux.subscriberContext(reactorCtx -> reactorCtx.put("foo", "bar"));
        StepVerifier.create(flux1)
                    .expectSubscriptionMatches(s -> ctxExists(ctx))
                    .expectNextMatches(s -> ctxExists(ctx) && "baz".equals(s))
                    .verifyComplete();
    }

    private static <T> Flux<T> addCallbacks(Flux<T> flux, ClientRequestContext ctx) {
        return flux.doFirst(() -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnSubscribe(s -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnRequest(l -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnNext(foo -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnComplete(() -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnEach(s -> assertThat(ctxExists(ctx)).isTrue())
                   .doOnError(t -> assertThat(ctxExists(ctx)).isTrue())
                   .doAfterTerminate(() -> assertThat(ctxExists(ctx)).isTrue());
        // doOnCancel and doFinally do not have context because we cannot add a hook to the cancel.
    }
}
