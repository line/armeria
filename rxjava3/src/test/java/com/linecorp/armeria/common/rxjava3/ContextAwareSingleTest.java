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

package com.linecorp.armeria.common.rxjava3;

import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.assertCtxInCallbacks;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.assertCurrentCtxIsNotNull;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.assertCurrentCtxIsNull;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.assertSameContext;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newSingle;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newSingleWithoutCtx;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newTestObserver;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newTestSubscriber;
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

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareSingleTest {
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
    public void single() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_error() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> maybe = assertCtxInCallbacks(
                Single.create(emitter -> {
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
    public void single_cancel() {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx);

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(single.test(true).isDisposed()).isTrue();
        }
    }

    @Test
    public void single_delaySubscription() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx)
                .delaySubscription(assertCtxInCallbacks(
                        Single.create(emitter -> {
                            assertSameContext(ctx);
                            executor.execute(() -> emitter.onSuccess("other"));
                        }), ctx))
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_delay() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx)
                .delay(1, TimeUnit.SECONDS)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_observeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx)
                .observeOn(Schedulers.computation())
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_contains() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx)
                .contains("success")
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue(Boolean.TRUE);
    }

    @Test
    public void single_retry() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final AtomicInteger counter = new AtomicInteger();
        final Single<Object> single = assertCtxInCallbacks(
                Single.create(emitter -> {
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
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_toFlowable() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = newSingle("success", ctx)
                .toFlowable()
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .firstOrError()
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_concatWith() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newSingle("success1", ctx)
                .concatWith(newSingle("success2", ctx))
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMapSingle(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.subscribe(testObserver);
        }
        testObserver.await().assertValues("success1", "success2");
    }

    @Test
    public void single_repeat() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newSingle("success", ctx)
                .repeat(3)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMapSingle(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.subscribe(testObserver);
        }
        testObserver.await().assertValues("success", "success", "success");
    }

    @Test
    public void single_subscribeOutsideCtx() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        Single<Object> single =
                Single.create(
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
                          return newSingleWithoutCtx(o);
                      });

        final TestObserver<Object> testObserver = TestObserver.create();
        try (SafeCloseable ignored = ctx.push()) {
            // Ctx is pushed when subscribing, so the tasks inside this test won't have ctx.
            single = single.map(o -> {
                assertCurrentCtxIsNull();
                return o;
            });
        }
        single.subscribe(testObserver);
        testObserver.await().assertValue("success");
    }

    @Test
    public void single_zip() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single =
                Single.zip(newSingle("Hello", ctx),
                           newSingle("World", ctx),
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
                          return newSingle(o, ctx);
                      });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("Hello World");
    }

    /**
     * To make cache works, we need to use subscribeOn to discontinue the chain.
     */
    @Test
    public void single_cache() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final ServiceRequestContext ctx1 = newContext();
        final Single<Object> single =
                Single.create(
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
                          return newSingleWithoutCtx(o);
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
            single.map(o -> {
                assertSameContext(ctx1);
                return o;
            }).subscribe(testObserver1);
            countDownLatch.countDown();
        }

        final ServiceRequestContext ctx2 = newContext();
        System.out.println(ctx1 + ", " + ctx2);
        final TestObserver<Object> testObserver2 = newTestObserver(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            single.map(o -> {
                assertSameContext(ctx2);
                return o;
            }).subscribe(testObserver2);
            countDownLatch.countDown();
        }

        testObserver1.await().assertValue("success");
        testObserver2.await().assertValue("success");
    }

    @Test
    public void single_subscribeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single =
                Single.create(
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
            single.subscribe(testObserver);
        }
        testObserver.await().assertValue("success");
    }
}
