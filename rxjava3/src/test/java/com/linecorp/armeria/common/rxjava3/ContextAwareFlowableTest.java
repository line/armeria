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

import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.assertCurrentCtxIsNull;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.assertSameContext;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newFlowable;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newFlowableWithoutCtx;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newTestSubscriber;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.flowables.ConnectableFlowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareFlowableTest {
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
    public void flowable() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable =
                Flowable.create(
                        emitter -> {
                            assertSameContext(ctx);
                            executor.execute(() -> {
                                for (int i = 0; i < 5; i++) {
                                    emitter.onNext("success");
                                }
                                emitter.onComplete();
                            });
                        }, BackpressureStrategy.BUFFER)
                        .map(o -> {
                            assertSameContext(ctx);
                            return o;
                        })
                        .flatMap(o -> {
                            assertSameContext(ctx);
                            return newFlowable(o, 5, ctx);
                        });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void flowable_publish() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final ConnectableFlowable<Object> flowable =
                Flowable.create(
                        emitter -> {
                            assertCurrentCtxIsNull();
                            executor.execute(() -> {
                                for (int i = 0; i < 5; i++) {
                                    emitter.onNext("success");
                                }
                                emitter.onComplete();
                            });
                        }, BackpressureStrategy.BUFFER)
                        .map(o -> {
                            assertCurrentCtxIsNull();
                            return o;
                        })
                        .flatMap(o -> {
                            assertCurrentCtxIsNull();
                            return newFlowableWithoutCtx(o, 5);
                        })
                        .observeOn(Schedulers.computation())
                        .publish();

        final ServiceRequestContext ctx2 = newContext();
        final TestSubscriber<Object> testObserver2 = newTestSubscriber(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            flowable.map(o -> {
                assertSameContext(ctx2);
                return o;
            }).subscribe(testObserver2);
        }

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        // Must be outside of ctx scope to avoid IllegalContextPushingException.
        flowable.connect();

        testObserver.await().assertValueCount(25);
        testObserver2.await().assertValueCount(25);
    }
}
