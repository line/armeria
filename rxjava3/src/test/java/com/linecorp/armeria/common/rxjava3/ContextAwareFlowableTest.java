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
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newBackpressureAwareFlowable;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newFlowable;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newFlowableWithoutCtx;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newSingle;
import static com.linecorp.armeria.common.rxjava3.CtxTestUtil.newTestSubscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.flowables.ConnectableFlowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareFlowableTest {
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
        final Flowable<Object> flowable = newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
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

    /**
     * Generating backpressure-aware streams.
     * Due to entry has possibility to be requested multiple times in different thread,
     * RequestContextSubscriber needs to put upstream's request/cancel in the scope.
     */
    @ParameterizedTest
    @MethodSource("flowableProvider")
    public void flowable_request(Flowable<Object> upstream, ServiceRequestContext ctx)
            throws InterruptedException {
        final Flowable<Object> flowable =
                upstream.observeOn(Schedulers.computation(), false, 2)
                        .flatMap(o -> {
                            assertSameContext(ctx);
                            return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
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
    public void flowable_cancel() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(flowable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).test(1, true).isCancelled()).isTrue();
        }
    }

    @Test
    public void flowable_buffer() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .buffer(5)
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(5);
    }

    @Test
    public void flowable_concatMap() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .concatMapSingle(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestSubscriber<Object> testObserver = newTestSubscriber(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            flowable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(5);
    }

    @Test
    public void flowable_concatMapEager() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .concatMapEager(o -> {
                    assertSameContext(ctx);
                    return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
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
    public void flowable_subscribeOutsideCtx() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        Flowable<Object> flowable = newFlowableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5))
                .subscribeOn(Schedulers.computation())
                .map(o -> {
                    assertCurrentCtxIsNull();
                    return o;
                })
                .flatMap(o -> {
                    assertCurrentCtxIsNull();
                    return newFlowableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5));
                });

        try (SafeCloseable ignored = ctx.push()) {
            flowable = flowable.map(o -> {
                assertCurrentCtxIsNull();
                return o;
            });
        }
        final TestSubscriber<Object> testObserver = TestSubscriber.create();
        flowable.subscribe(testObserver);
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void flowable_observeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .observeOn(Schedulers.computation())
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
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
    public void flowable_subscribeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Flowable<Object> flowable = newFlowableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5))
                .subscribeOn(Schedulers.computation())
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
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
        final ConnectableFlowable<Integer> flowable = newFlowableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5))
                .map(o -> {
                    assertCurrentCtxIsNull();
                    return o;
                })
                .flatMap(o -> {
                    assertCurrentCtxIsNull();
                    return newFlowableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5));
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

    static Stream<Arguments> flowableProvider() {
        final ServiceRequestContext ctx1 = newContext();
        final ServiceRequestContext ctx2 = newContext();
        return Stream.of(arguments(newBackpressureAwareFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx1), ctx1),
                         arguments(newFlowable(ImmutableList.of(1, 2, 3, 4, 5), ctx2), ctx2));
    }
}
