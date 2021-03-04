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

package com.linecorp.armeria.common.rxjava2;

import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertCtxInCallbacks;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertCurrentCtxIsNotNull;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertCurrentCtxIsNull;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertSameContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newMaybe;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newMaybeWithoutCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newTestObserver;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newTestSubscriber;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subscribers.TestSubscriber;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareMaybeTest {
    private final ScheduledExecutorService scheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor();
    private final Executor executor =
            command -> scheduledExecutorService.schedule(command, 10, TimeUnit.MILLISECONDS);

    @BeforeAll
    static void setUp() {
        RequestContextAssembly.enable();
    }

    @AfterAll
    static void tearDown() {
        RequestContextAssembly.disable();
    }

    @Test
    public void maybe() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_complete() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = assertCtxInCallbacks(
                Maybe.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(emitter::onComplete);
                }), ctx);

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void maybe_error() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = assertCtxInCallbacks(
                Maybe.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> emitter.onError(new IllegalStateException()));
                }), ctx);

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertError(IllegalStateException.class);
    }

    @Test
    public void maybe_delaySubscription() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx)
                .delaySubscription(Flowable.timer(1, TimeUnit.MILLISECONDS))
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_delay() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx)
                .delay(1, TimeUnit.SECONDS)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_observeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx)
                .observeOn(Schedulers.computation())
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_contains() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx)
                .contains("success")
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMapMaybe(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue(Boolean.TRUE);
    }

    @Test
    public void maybe_cancel() {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx);

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(maybe.test(true).isDisposed()).isTrue();
        }
    }

    @Test
    public void maybe_retry() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final AtomicInteger counter = new AtomicInteger();
        final Maybe<Object> maybe = assertCtxInCallbacks(
                Maybe.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> {
                        if (counter.getAndIncrement() == 2) {
                            emitter.onSuccess("success");
                        } else {
                            emitter.onError(new IllegalStateException());
                        }
                    });
                }), ctx)
                .retry()
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_toFlowable() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe = newMaybe("success", ctx)
                .toFlowable()
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .firstOrError()
                .flatMapMaybe(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_concatWith() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newMaybe("success1", ctx)
                .concatWith(newMaybe("success2", ctx))
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.subscribe(testObserver);
        }
        testObserver.await().assertValues("success1", "success2");
    }

    @Test
    public void maybe_repeat() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newMaybe("success", ctx)
                .repeat(3)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMapMaybe(o -> {
                    assertSameContext(ctx);
                    return newMaybe(o, ctx);
                });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.subscribe(testObserver);
        }
        testObserver.await().assertValues("success", "success", "success");
    }

    @Test
    public void maybe_subscribeOutsideCtx() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        Maybe<Object> maybe =
                Maybe.create(
                        emitter -> {
                            assertCurrentCtxIsNull();
                            executor.execute(() -> emitter.onSuccess("success"));
                        })
                     .map(o -> {
                         assertCurrentCtxIsNull();
                         return o;
                     })
                     .flatMap(o -> {
                         assertCurrentCtxIsNull();
                         return newMaybeWithoutCtx(o);
                     });

        final TestObserver<Object> testObserver = TestObserver.create();
        try (SafeCloseable ignored = ctx.push()) {
            // Ctx is pushed when subscribing, so the tasks inside this test won't have ctx.
            maybe = maybe.map(o -> {
                assertCurrentCtxIsNull();
                return o;
            });
        }
        maybe.subscribe(testObserver);
        testObserver.await().assertValue("success");
    }

    @Test
    public void maybe_zip() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe =
                Maybe.zip(newMaybe("Hello", ctx),
                          newMaybe("World", ctx),
                          (value1, value2) -> {
                              assertSameContext(ctx);
                              return value1 + " " + value2;
                          })
                     .map(o -> {
                         assertSameContext(ctx);
                         return o;
                     })
                     .flatMap(o -> {
                         assertSameContext(ctx);
                         return newMaybe(o, ctx);
                     });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("Hello World");
    }

    /**
     * To make cache works, we need to use subscribeOn to discontinue the chain.
     */
    @Test
    public void maybe_cache() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final ServiceRequestContext ctx1 = newContext();
        final Maybe<Object> maybe =
                Maybe.create(
                        emitter -> {
                            assertCurrentCtxIsNull();
                            executor.execute(() -> {
                                try {
                                    countDownLatch.await();
                                    emitter.onSuccess("success");
                                } catch (InterruptedException ignored) {
                                }
                            });
                        })
                     .map(o -> {
                         assertCurrentCtxIsNull();
                         return o;
                     })
                     .flatMap(o -> {
                         assertCurrentCtxIsNull();
                         return newMaybeWithoutCtx(o);
                     })
                     .cache()
                     // Discontinue the subscribe chain to avoid IllegalContextPushingException.
                     .subscribeOn(Schedulers.computation())
                     .map(o -> {
                         assertCurrentCtxIsNotNull();
                         return o;
                     });

        final TestObserver<Object> testObserver1 = newTestObserver(ctx1);
        try (SafeCloseable ignored = ctx1.push()) {
            maybe.map(o -> {
                assertSameContext(ctx1);
                return o;
            }).subscribe(testObserver1);
            countDownLatch.countDown();
        }

        final ServiceRequestContext ctx2 = newContext();
        System.out.println(ctx1 + ", " + ctx2);
        final TestObserver<Object> testObserver2 = newTestObserver(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            maybe.map(o -> {
                assertSameContext(ctx2);
                return o;
            }).subscribe(testObserver2);
            countDownLatch.countDown();
        }

        testObserver1.await().assertValue("success");
        testObserver2.await().assertValue("success");
    }

    @Test
    public void maybe_subscribeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Maybe<Object> maybe =
                Maybe.create(
                        emitter -> {
                            assertCurrentCtxIsNull();
                            executor.execute(() -> emitter.onSuccess("success"));
                        })
                     .map(o -> {
                         assertCurrentCtxIsNull();
                         return o;
                     })
                     // ctx won't exist if upstream subscribed with non-ctx thread.
                     .subscribeOn(Schedulers.computation())
                     .map(o -> {
                         assertSameContext(ctx);
                         return o;
                     });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            maybe.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }
}
