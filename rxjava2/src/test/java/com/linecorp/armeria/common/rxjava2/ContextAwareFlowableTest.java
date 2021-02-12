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

import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.ctxExists;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.currentCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newFlowable;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newFlowableWithoutCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newTestSubscriber;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.flowables.ConnectableFlowable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subscribers.TestSubscriber;

public class ContextAwareFlowableTest {

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
    public void flowable() {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable =
                Flowable.create(
                        emitter -> {
                            assertThat(ctxExists(ctx)).isTrue();
                            executor.execute(() -> {
                                for (int i = 0; i < 5; i++) {
                                    emitter.onNext("success");
                                }
                                emitter.onComplete();
                            });
                        }, BackpressureStrategy.BUFFER)
                        .map(o -> {
                            assertThat(ctxExists(ctx)).isTrue();
                            return o;
                        })
                        .flatMap(o -> {
                            assertThat(ctxExists(ctx)).isTrue();
                            return newFlowable(o, 5, ctx);
                        });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.map(o -> {
                assertThat(ctxExists(ctx)).isTrue();
                return o;
            }).subscribe(testObserver);
        }
        testObserver.awaitTerminalEvent();
        testObserver.assertValueCount(25);
    }

    @Test
    public void flowable_publish() {
        final ServiceRequestContext ctx = newContext();
        final ConnectableFlowable<Object> flowable =
                Flowable.create(
                        emitter -> {
                            assertThat(currentCtx()).isNull();
                            executor.execute(() -> {
                                for (int i = 0; i < 5; i++) {
                                    emitter.onNext("success");
                                }
                                emitter.onComplete();
                            });
                        }, BackpressureStrategy.BUFFER)
                        .map(o -> {
                            assertThat(currentCtx()).isNull();
                            return o;
                        })
                        .flatMap(o -> {
                            assertThat(currentCtx()).isNull();
                            return newFlowableWithoutCtx(o, 5);
                        })
                        .observeOn(Schedulers.computation())
                        .publish();

        final ServiceRequestContext ctx2 = newContext();
        final TestSubscriber<Object> testObserver2 = newTestSubscriber(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            flowable.map(o -> {
                assertThat(ctxExists(ctx2)).isTrue();
                return o;
            }).subscribe(testObserver2);
        }

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.map(o -> {
                assertThat(ctxExists(ctx)).isTrue();
                return o;
            }).subscribe(testObserver);
        }
        // Must be outside of ctx scope to avoid IllegalContextPushingException.
        flowable.connect();

        testObserver.awaitTerminalEvent();
        testObserver.assertValueCount(25);
        testObserver2.awaitTerminalEvent();
        testObserver2.assertValueCount(25);
    }
}
