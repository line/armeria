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
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newCompletable;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newCompletableWithoutCtx;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newTestObserver;
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

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareCompletableTest {
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
    public void completable() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx).andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_error() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = assertCtxInCallbacks(
                Completable.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> emitter.onError(new IllegalStateException()));
                }), ctx);

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertError(IllegalStateException.class);
    }

    @Test
    public void completable_delaySubscription() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx).delaySubscription(1, TimeUnit.MILLISECONDS);

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_delay() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx)
                .delay(1, TimeUnit.SECONDS)
                .andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_observeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx)
                .observeOn(Schedulers.computation())
                .andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_cancel() {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx);

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(completable.test(true).isDisposed()).isTrue();
        }
    }

    @Test
    public void completable_retry() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final AtomicInteger counter = new AtomicInteger();
        final Completable completable = assertCtxInCallbacks(
                Completable.create(emitter -> {
                    assertSameContext(ctx);
                    executor.execute(() -> {
                        if (counter.getAndIncrement() == 2) {
                            emitter.onComplete();
                        } else {
                            emitter.onError(new IllegalStateException());
                        }
                    });
                }), ctx)
                .retry()
                .andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_toFlowable() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx)
                .toFlowable()
                .ignoreElements();

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_concatWith() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx)
                .concatWith(newCompletable(ctx))
                .andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_repeat() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable = newCompletable(ctx)
                .repeat(3)
                .andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }

    @Test
    public void completable_subscribeOutsideCtx() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        Completable completable = newCompletableWithoutCtx();

        final TestObserver<Object> testObserver = TestObserver.create();
        try (SafeCloseable ignored = ctx.push()) {
            // Ctx is pushed when subscribing, so the tasks inside this test won't have ctx.
            completable = completable.andThen(newCompletableWithoutCtx());
        }
        completable.subscribe(testObserver);
        testObserver.await().assertComplete();
    }

    /**
     * To make cache works, we need to use subscribeOn to discontinue the chain.
     */
    @Test
    public void completable_cache() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final ServiceRequestContext ctx1 = newContext();
        final Completable completable =
                Completable.create(
                        emitter -> {
                            assertCurrentCtxIsNull();
                            executor.execute(() -> {
                                try {
                                    countDownLatch.await();
                                    emitter.onComplete();
                                } catch (InterruptedException ignored) {
                                }
                            });
                        })
                           .andThen(Completable.create(emitter -> {
                               assertCurrentCtxIsNull();
                               executor.execute(emitter::onComplete);
                           }))
                           .cache()
                           // Discontinue the subscribe chain to avoid IllegalContextPushingException.
                           .subscribeOn(Schedulers.computation())
                           .andThen(Completable.create(emitter -> {
                               // Context is decided by caller.
                               assertCurrentCtxIsNotNull();
                               executor.execute(emitter::onComplete);
                           }));

        final TestObserver<Object> testObserver1 = newTestObserver(ctx1);
        try (SafeCloseable ignored = ctx1.push()) {
            completable.andThen(newCompletable(ctx1)).subscribe(testObserver1);
            countDownLatch.countDown();
        }

        final ServiceRequestContext ctx2 = newContext();
        System.out.println(ctx1 + ", " + ctx2);
        final TestObserver<Object> testObserver2 = newTestObserver(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            completable.andThen(newCompletable(ctx2)).subscribe(testObserver2);
            countDownLatch.countDown();
        }

        testObserver1.await().assertComplete();
        testObserver2.await().assertComplete();
    }

    @Test
    public void completable_subscribeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Completable completable =
                Completable.create(
                        emitter -> {
                            assertCurrentCtxIsNull();
                            executor.execute(emitter::onComplete);
                        })
                           .andThen(newCompletableWithoutCtx())
                           // ctx won't exist if upstream subscribed with non-ctx thread.
                           .subscribeOn(Schedulers.computation())
                           .andThen(newCompletable(ctx));

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            completable.subscribe(testObserver);
        }
        testObserver.await().assertComplete();
    }
}
