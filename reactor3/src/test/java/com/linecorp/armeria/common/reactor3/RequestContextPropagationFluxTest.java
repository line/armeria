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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContextAccessor;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;

import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@GenerateNativeImageTrace
class RequestContextPropagationFluxTest {

    static Stream<Arguments> provideContextWriteAndCaptureTestCase() {
        return Stream.of(
                // shouldContextWrite, shouldContextCapture.
                Arguments.of(true, false),
                Arguments.of(false, true)
        );
    }

    @BeforeAll
    static void setUp() {
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void tearDown() {
        Hooks.disableAutomaticContextPropagation();
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxError(boolean shouldContextWrite,
                   boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.error(() -> {
            // This is called twice. after publishOn() and verifyErrorMatches()
            // After publishOn(), ctxExists(ctx) should be false.
            // On the other hand, it should be True due to ContextPropagation.
            return new AnticipatedException();
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(flux)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxFromPublisher(boolean shouldContextWrite,
                           boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.from(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.onSubscribe(noopSubscription());
            s.onNext("foo");
            s.onComplete();
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxCreate(boolean shouldContextWrite,
                    boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.create(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.next("foo");
            s.complete();
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxCreate_error(boolean shouldContextWrite,
                          boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.create(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.error(new AnticipatedException());
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(flux)
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxConcat(boolean shouldContextWrite,
                    boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.concat(Mono.fromSupplier(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return "foo";
        }), Mono.fromCallable(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return "bar";
        })).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .expectNextMatches(s -> "bar".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .expectNextMatches(s -> "bar".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxDefer(boolean shouldContextWrite,
                   boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.defer(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return Flux.just("foo");
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxFromStream(boolean shouldContextWrite,
                        boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.fromStream(() -> {
            assertThat(ctxExists(ctx)).isTrue();
            return Stream.of("foo");
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxCombineLatest(boolean shouldContextWrite,
                           boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.combineLatest(Mono.just("foo"), Mono.just("bar"), (a, b) -> {
            assertThat(ctxExists(ctx)).isTrue();
            return a;
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxGenerate(boolean shouldContextWrite,
                      boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.generate(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.next("foo");
            s.complete();
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxMerge(boolean shouldContextWrite,
                   boolean shouldContextCapture) {
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
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .expectNextMatches(s -> "bar".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .expectNextMatches(s -> "bar".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxPush(boolean shouldContextWrite,
                  boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.push(s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.next("foo");
            s.complete();
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxSwitchOnNext(boolean shouldContextWrite,
                          boolean shouldContextCapture) {
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
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxZip(boolean shouldContextWrite,
                 boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.zip(Mono.just("foo"), Mono.just("bar"), (foo, bar) -> {
            assertThat(ctxExists(ctx)).isTrue();
            return foo;
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxInterval(boolean shouldContextWrite,
                      boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        flux = addCallbacks(Flux.interval(Duration.ofMillis(100)).take(2).concatMap(a -> {
            assertThat(ctxExists(ctx)).isTrue();
            return Mono.just("foo");
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxConcatDelayError(boolean shouldContextWrite,
                              boolean shouldContextCapture) {
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
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .expectNextMatches(s -> "bar".equals(s))
                            .verifyErrorMatches(t -> t instanceof AnticipatedException);
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .expectNextMatches(s -> "bar".equals(s))
                        .verifyErrorMatches(t -> t instanceof AnticipatedException);
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void fluxTransform(boolean shouldContextWrite,
                       boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<Object> flux;

        flux = addCallbacks(Flux.just("foo").transform(fooFlux -> s -> {
            assertThat(ctxExists(ctx)).isTrue();
            s.onSubscribe(noopSubscription());
            s.onNext(fooFlux.blockFirst());
            s.onComplete();
        }).publishOn(Schedulers.single()), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void connectableFlux(boolean shouldContextWrite,
                         boolean shouldContextCapture) {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        final ConnectableFlux<String> connectableFlux = Flux.just("foo").publish();
        flux = addCallbacks(connectableFlux.autoConnect(2).publishOn(Schedulers.single()),
                            ctx,
                            shouldContextWrite,
                            shouldContextCapture);

        if (shouldContextCapture) {
            try (SafeCloseable ignored = ctx.push()) {
                flux.subscribe().dispose();
                StepVerifier.create(flux)
                            .expectNextMatches(s -> "foo".equals(s))
                            .verifyComplete();
            }
        } else {
            flux.subscribe().dispose();
            StepVerifier.create(flux)
                        .expectNextMatches(s -> "foo".equals(s))
                        .verifyComplete();
        }
    }

    @ParameterizedTest
    @MethodSource("provideContextWriteAndCaptureTestCase")
    void connectableFlux_dispose(boolean shouldContextWrite,
                                 boolean shouldContextCapture) throws InterruptedException {
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        final ConnectableFlux<String> connectableFlux = Flux.just("foo").publish();
        flux = addCallbacks(connectableFlux.autoConnect(2, disposable -> {
            assertThat(ctxExists(ctx)).isTrue();
        }).publishOn(Schedulers.newSingle("aaa")), ctx, shouldContextWrite, shouldContextCapture);

        if (shouldContextCapture) {
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
                    .expectNextMatches(s -> "baz".equals(s))
                    .verifyComplete();
    }

    @Test
    void ctxShouldBeCleanUpEvenIfErrorOccursDuringReactorOperationOnSchedulerThread() {
        // Given
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        // When
        flux = Flux.just("Hello", "Hi")
                   .subscribeOn(Schedulers.single())
                   .delayElements(Duration.ofMillis(1000))
                   .map(s -> {
                       if (s.equals("Hello")) {
                           throw new RuntimeException();
                       }
                       return s;
                   })
                   .contextWrite(Context.of(RequestContextAccessor.accessorKey(), ctx));

        // Then
        StepVerifier.create(flux)
                    .expectError(RuntimeException.class)
                    .verify();

        final Flux<String> toVerifyFlux = Flux.just("Dummy")
                                        .subscribeOn(Schedulers.single())
                                        .doOnNext(s -> assertThat(ctxExists(ctx)).isFalse());

        StepVerifier.create(toVerifyFlux)
                    .expectNext("Dummy")
                    .verifyComplete();
    }

    @Test
    void ctxShouldBeCleanUpEvenIfErrorOccursDuringReactorOperationOnMainThread() {
        // Given
        final ClientRequestContext ctx = newContext();
        final Flux<String> flux;

        // When
        flux = Flux.just("Hello", "Hi")
                   .map(s -> {
                       if (s.equals("Hello")) {
                           throw new RuntimeException();
                       }
                       return s;
                   })
                   .contextWrite(Context.of(RequestContextAccessor.accessorKey(), ctx));

        // Then
        StepVerifier.create(flux)
                    .expectError(RuntimeException.class)
                    .verify();

        assertThat(ctxExists(ctx)).isFalse();
    }

    private static <T> Flux<T> addCallbacks(Flux<T> flux0,
                                            ClientRequestContext ctx,
                                            boolean shouldContextWrite,
                                            boolean shouldContextCapture) {
        // doOnCancel and doFinally do not have context because we cannot add a hook to the cancel.
        final Flux<T> flux = flux0.doFirst(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnSubscribe(s -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnRequest(l -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnNext(foo -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnComplete(() -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnEach(s -> assertThat(ctxExists(ctx)).isTrue())
                                  .doOnError(t -> assertThat(ctxExists(ctx)).isTrue())
                                  .doAfterTerminate(() -> assertThat(ctxExists(ctx)).isTrue());

        if (shouldContextWrite) {
            return flux.contextWrite(Context.of(RequestContextAccessor.accessorKey(), ctx));
        }

        if (shouldContextCapture) {
            return flux.contextCapture();
        }

        return flux;
    }
}
