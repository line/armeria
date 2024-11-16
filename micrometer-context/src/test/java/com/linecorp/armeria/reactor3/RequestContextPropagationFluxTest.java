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
package com.linecorp.armeria.reactor3;

import static com.linecorp.armeria.reactor3.RequestContextPropagationMonoTest.ctxExists;
import static com.linecorp.armeria.reactor3.RequestContextPropagationMonoTest.newContext;
import static com.linecorp.armeria.reactor3.RequestContextPropagationMonoTest.noopSubscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Publisher;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.micrometer.context.RequestContextThreadLocalAccessor;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;

import io.micrometer.context.ContextRegistry;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@GenerateNativeImageTrace
class RequestContextPropagationFluxTest {

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
    void fluxError(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        final AtomicBoolean atomicBoolean = new AtomicBoolean();
        flux = addCallbacks(Flux.error(() -> {
            if (!atomicBoolean.getAndSet(true)) {
                // Flux.error().publishOn() calls this error supplier immediately to see if it can retrieve
                // the value via Callable.call() without ctx.
                assertThat(ctxExists(ctx)).isFalse();
            } else {
                assertThat(ctxExists(ctx)).isTrue();
            }
            return new AnticipatedException();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(flux)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxFromPublisher(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.from(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.onSubscribe(noopSubscription());
            s.onNext("foo");
            s.onComplete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxCreate(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.create(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.next("foo");
            s.complete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxCreate_error(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.create(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.error(new AnticipatedException());
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(flux)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxConcat(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.concat(Mono.fromSupplier(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return "foo";
        }), Mono.fromCallable(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return "bar";
        })).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .expectNextMatches("bar"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .expectNextMatches("bar"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxDefer(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.defer(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return Flux.just("foo");
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxFromStream(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.fromStream(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return Stream.of("foo");
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxCombineLatest(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.combineLatest(Mono.just("foo"), Mono.just("bar"), (a, b) -> {
            assertThat(ctxExists(ctx)).isTrue();
            return a;
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxGenerate(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.generate(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.next("foo");
            s.complete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxMerge(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

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
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .expectNextMatches("bar"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .expectNextMatches("bar"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxPush(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.push(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.next("foo");
            s.complete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxSwitchOnNext(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

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
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxZip(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.zip(Mono.just("foo"), Mono.just("bar"), (foo, bar) -> {
            assertThat(ctxExists(ctx)).isTrue();
            return foo;
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxInterval(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.interval(Duration.ofMillis(100)).take(2).concatMap(a -> {
            assertThat(ctxExists(ctx)).isTrue();
            return Mono.just("foo");
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxConcatDelayError(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.concatDelayError(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.onSubscribe(noopSubscription());
            s.onNext("foo");
            s.onError(new AnticipatedException());
        }, s -> {
            s.onSubscribe(noopSubscription());
            s.onNext("bar");
            s.onComplete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .expectNextMatches("bar"::equals)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .expectNextMatches("bar"::equals)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void fluxTransform(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.just("foo").transform(fooFlux -> s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.onSubscribe(noopSubscription());
            s.onNext(fooFlux.blockFirst());
            s.onComplete();
        }).publishOn(Schedulers.single()), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void connectableFlux(boolean useContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        final ConnectableFlux<String> connectableFlux = Flux.just("foo").publish();
        flux = addCallbacks(connectableFlux.autoConnect(2).publishOn(Schedulers.single()),
                            ctx,
                            useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                flux.subscribe().dispose();
                StepVerifier.create(flux)
                            .expectNextMatches("foo"::equals)
                            .verifyComplete();
            }
        } else {
            flux.subscribe().dispose();
            StepVerifier.create(flux)
                        .expectNextMatches("foo"::equals)
                        .verifyComplete();
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void connectableFlux_dispose(boolean useContextCapture) throws InterruptedException {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        final ConnectableFlux<String> connectableFlux = Flux.just("foo").publish();
        flux = addCallbacks(connectableFlux.autoConnect(2, disposable -> {
            assertThat(ctxExists(ctx)).isTrue();
        }).publishOn(Schedulers.newSingle("aaa")), ctx, useContextCapture);

        if (useContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                final Disposable disposable1 = flux.subscribe();
                await().pollDelay(Duration.ofMillis(200)).until(() -> !disposable1.isDisposed());
                final Disposable disposable2 = flux.subscribe();
                await().untilAsserted(() -> {
                    assertThat(disposable1.isDisposed()).isTrue();
                    assertThat(disposable2.isDisposed()).isTrue();
                });
            }
        } else {
            final Disposable disposable1 = flux.subscribe();
            await().pollDelay(Duration.ofMillis(200)).until(() -> !disposable1.isDisposed());
            final Disposable disposable2 = flux.subscribe();
            await().untilAsserted(() -> {
                assertThat(disposable1.isDisposed()).isTrue();
                assertThat(disposable2.isDisposed()).isTrue();
            });
        }
        assertThat(ctxExists(ctx)).isFalse();
    }

    @Test
    void subscriberContextIsNotMissing() {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = Flux.deferContextual(reactorCtx -> {
            assertThat((String) reactorCtx.get("foo")).isEqualTo("bar");
            return Flux.just("baz");
        });

        final Flux<String> flux1 = flux.contextWrite(reactorCtx -> reactorCtx.put("foo", "bar"));
        StepVerifier.create(flux1)
                    .expectNextMatches("baz"::equals)
                    .verifyComplete();
        assertThat(ctxExists(ctx)).isFalse();
    }

    @Test
    void ctxShouldBeCleanUpEvenIfErrorOccursDuringReactorOperationOnSchedulerThread()
            throws InterruptedException {
        // Given
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;
        final Scheduler single = Schedulers.single();

        // When
        flux = Flux.just("Hello", "Hi")
                   .subscribeOn(single)
                   .delayElements(Duration.ofMillis(1000))
                   .map(s -> {
                       if ("Hello".equals(s)) {
                           throw new RuntimeException();
                       }
                       return s;
                   })
                   .contextWrite(Context.of(RequestContext.class, ctx));

        // Then
        StepVerifier.create(flux)
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

    private static <T> Flux<T> addCallbacks(Flux<T> flux0,
                                            ClientRequestContext ctx,
                                            boolean useContextCapture) {
        final Flux<T> flux = flux0.doFirst(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnSubscribe(s -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnRequest(l -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnNext(foo -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnComplete(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnEach(s -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnError(t -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnCancel(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doFinally(t -> assertThat(ctxExists(ctx)).isTrue())
                                  .doAfterTerminate(() -> assertThat(ctxExists(ctx)).isTrue());

        if (useContextCapture) {
            return flux.contextCapture();
        }
        return flux.contextWrite(Context.of(RequestContext.class, ctx));
    }
}
