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

import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.currentCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newSingle;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newSingleWithoutCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newTestObserver;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.Single;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.Schedulers;

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
    public void single() {
        final ServiceRequestContext ctx = CtxTestUtil.newContext();
        final Single<Object> single =
                CtxTestUtil.addCallbacks(
                        Single.create(emitter -> {
                            assertThat(CtxTestUtil.ctxExists(ctx)).isTrue();
                            executor.execute(() -> emitter.onSuccess("success"));
                        }), ctx)
                           .map(o -> {
                               assertThat(CtxTestUtil.ctxExists(ctx)).isTrue();
                               return o;
                           })
                           .flatMap(o -> {
                               assertThat(CtxTestUtil.ctxExists(ctx)).isTrue();
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
        final ServiceRequestContext ctx = CtxTestUtil.newContext();
        Single<Object> single =
                Single.create(
                        emitter -> {
                            assertThat(CtxTestUtil.currentCtx()).isNull();
                            executor.execute(() -> emitter.onSuccess("success"));
                        })
                      .map(o -> {
                          assertThat(CtxTestUtil.currentCtx()).isNull();
                          return o;
                      })
                      .flatMap(o -> {
                          assertThat(CtxTestUtil.currentCtx()).isNull();
                          return newSingleWithoutCtx(o);
                      });

        final TestObserver<Object> testObserver = TestObserver.create();
        try (SafeCloseable ignored = ctx.push()) {
            // Ctx is pushed when subscribing, so the tasks inside this test won't have ctx.
            single = single.map(o -> {
                assertThat(CtxTestUtil.currentCtx()).isNull();
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
        final ServiceRequestContext ctx1 = CtxTestUtil.newContext();
        final Single<Object> single =
                Single.create(
                        emitter -> {
                            assertThat(CtxTestUtil.currentCtx()).isNull();
                            executor.execute(() -> {
                                try {
                                    countDownLatch.await();
                                    emitter.onSuccess("success");
                                } catch (InterruptedException ignored) {
                                }
                            });
                        })
                      .map(o -> {
                          assertThat(CtxTestUtil.currentCtx()).isNull();
                          return o;
                      })
                      .flatMap(o -> {
                          assertThat(CtxTestUtil.currentCtx()).isNull();
                          return newSingleWithoutCtx(o);
                      })
                      .cache()
                      // Discontinue the subscribe chain to avoid IllegalContextPushingException.
                      .subscribeOn(Schedulers.computation());

        final TestObserver<Object> testObserver1 = newTestObserver(ctx1);
        try (SafeCloseable ignored = ctx1.push()) {
            single.map(o -> {
                assertThat(CtxTestUtil.ctxExists(ctx1)).isTrue();
                return o;
            }).subscribe(testObserver1);
            countDownLatch.countDown();
        }

        final ServiceRequestContext ctx2 = CtxTestUtil.newContext();
        final TestObserver<Object> testObserver2 = newTestObserver(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            single.map(o -> {
                assertThat(CtxTestUtil.ctxExists(ctx2)).isTrue();
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
        final ServiceRequestContext ctx = CtxTestUtil.newContext();
        final Single<Object> single =
                Single.create(
                        emitter -> {
                            assertThat(currentCtx()).isNull();
                            executor.execute(() -> emitter.onSuccess("success"));
                        })
                      .map(o -> {
                          assertThat(currentCtx()).isNull();
                          return o;
                      })
                      // ctx won't exist if upstream subscribed with non-ctx thread.
                      .subscribeOn(Schedulers.computation())
                      .map(o -> {
                          assertThat(CtxTestUtil.ctxExists(ctx)).isTrue();
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
