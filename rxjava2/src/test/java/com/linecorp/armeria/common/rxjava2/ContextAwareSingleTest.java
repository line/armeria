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

import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.addCallbacks;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertCurrentCtxIsNull;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertSameContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newSingle;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newSingleWithoutCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newTestObserver;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.Single;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.Schedulers;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareSingleTest {
    private final Executor executor = Executors.newSingleThreadExecutor();

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
        final Single<Object> single = addCallbacks(
                Single.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> emitter.onSuccess("success"));
                }), ctx)
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
        testObserver.await().assertComplete().assertValue("success");
    }

    @Test
    public void single_cancel() {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = addCallbacks(
                Single.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> emitter.onSuccess("success"));
                }), ctx);

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(single.test(true).isDisposed()).isTrue();
        }
    }

    @Test
    public void single_retry() {
        final ServiceRequestContext ctx = newContext();
        final AtomicInteger counter = new AtomicInteger();
        final Single<Object> single = addCallbacks(
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
        testObserver.awaitTerminalEvent();
        testObserver.assertValue("success");
    }

    @Test
    public void single_toFlowable() {
        final ServiceRequestContext ctx = newContext();
        final Single<Object> single = addCallbacks(
                Single.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> emitter.onSuccess("success"));
                }), ctx)
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
        testObserver.awaitTerminalEvent();
        testObserver.assertValue("success");
    }

    @Test
    public void single_subscribeOutsideCtx() {
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
        testObserver.awaitTerminalEvent();
        testObserver.assertValue("success");
    }

    /**
     * To make cache works, we need to use subscribeOn to discontinue the chain.
     */
    @Test
    public void single_cache() {
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
                      .subscribeOn(Schedulers.computation());

        final TestObserver<Object> testObserver1 = newTestObserver(ctx1);
        try (SafeCloseable ignored = ctx1.push()) {
            single.map(o -> {
                assertSameContext(ctx1);
                return o;
            }).subscribe(testObserver1);
            countDownLatch.countDown();
        }

        final ServiceRequestContext ctx2 = newContext();
        final TestObserver<Object> testObserver2 = newTestObserver(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            single.map(o -> {
                assertSameContext(ctx2);
                return o;
            }).subscribe(testObserver2);
            countDownLatch.countDown();
        }

        testObserver1.awaitTerminalEvent();
        testObserver2.awaitTerminalEvent();

        testObserver1.assertValue("success");
        testObserver2.assertValue("success");
    }

    @Test
    public void single_subscribeOn() {
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
        testObserver.awaitTerminalEvent();
        testObserver.assertValue("success");
    }
}
