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

import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertCurrentCtxIsNull;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.assertSameContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newContext;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newObservable;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newObservableWithoutCtx;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newSingle;
import static com.linecorp.armeria.common.rxjava2.CtxTestUtil.newTestObserver;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.Observable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.Schedulers;

@ExtendWith(RxErrorDetectExtension.class)
public class ContextAwareObservableTest {
    @BeforeAll
    static void setUp() {
        RequestContextAssembly.enable();
    }

    @AfterAll
    static void tearDown() {
        RequestContextAssembly.disable();
    }

    @Test
    public void observable() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void observable_generate() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable =
                Observable.generate(
                        () -> 1,
                        (value, emitter) -> {
                            assertSameContext(ctx);
                            if (value > 5) {
                                emitter.onComplete();
                            } else {
                                emitter.onNext(value);
                            }
                            return value + 1;
                        })
                          .flatMap(o -> {
                              assertSameContext(ctx);
                              return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                          })
                          .observeOn(Schedulers.computation(), false, 2)
                          .map(o -> {
                              assertSameContext(ctx);
                              return o;
                          })
                          .flatMap(o -> {
                              assertSameContext(ctx);
                              return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                          });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(125);
    }

    @Test
    public void observable_cancel() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).test(true).isDisposed()).isTrue();
        }
    }

    @Test
    public void observable_buffer() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .buffer(5)
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(5);
    }

    @Test
    public void observable_concatMap() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .concatMapSingle(o -> {
                    assertSameContext(ctx);
                    return newSingle(o, ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(5);
    }

    @Test
    public void observable_concatMapEager() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .concatMapEager(o -> {
                    assertSameContext(ctx);
                    return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void observable_subscribeOutsideCtx() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        Observable<Object> observable = newObservableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5))
                .subscribeOn(Schedulers.computation())
                .map(o -> {
                    assertCurrentCtxIsNull();
                    return o;
                })
                .flatMap(o -> {
                    assertCurrentCtxIsNull();
                    return newObservableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5));
                });

        try (SafeCloseable ignored = ctx.push()) {
            observable = observable.map(o -> {
                assertCurrentCtxIsNull();
                return o;
            });
        }
        final TestObserver<Object> testObserver = TestObserver.create();
        observable.subscribe(testObserver);
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void observable_observeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx)
                .observeOn(Schedulers.computation())
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void observable_subscribeOn() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final Observable<Object> observable = newObservableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5))
                .subscribeOn(Schedulers.computation())
                .map(o -> {
                    assertSameContext(ctx);
                    return o;
                })
                .flatMap(o -> {
                    assertSameContext(ctx);
                    return newObservable(ImmutableList.of(1, 2, 3, 4, 5), ctx);
                });

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        testObserver.await().assertValueCount(25);
    }

    @Test
    public void observable_publish() throws InterruptedException {
        final ServiceRequestContext ctx = newContext();
        final ConnectableObservable<Integer> observable = newObservableWithoutCtx(
                ImmutableList.of(1, 2, 3, 4, 5))
                .map(o -> {
                    assertCurrentCtxIsNull();
                    return o;
                })
                .flatMap(o -> {
                    assertCurrentCtxIsNull();
                    return newObservableWithoutCtx(ImmutableList.of(1, 2, 3, 4, 5));
                })
                .observeOn(Schedulers.computation())
                .publish();

        final ServiceRequestContext ctx2 = newContext();
        final TestObserver<Object> testObserver2 = newTestObserver(ctx2);
        try (SafeCloseable ignored = ctx2.push()) {
            observable.map(o -> {
                assertSameContext(ctx2);
                return o;
            }).subscribe(testObserver2);
        }

        final TestObserver<Object> testObserver = newTestObserver(ctx);
        try (SafeCloseable ignored = ctx.push()) {
            observable.map(o -> {
                assertSameContext(ctx);
                return o;
            }).subscribe(testObserver);
        }
        // Must be outside of ctx scope to avoid IllegalContextPushingException.
        observable.connect();

        testObserver.await().assertValueCount(25);
        testObserver2.await().assertValueCount(25);
    }
}
