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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ForkJoinPool;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.observers.TestObserver;
import io.reactivex.subscribers.TestSubscriber;

public final class CtxTestUtil {

    private CtxTestUtil() {}

    static TestObserver<Object> newTestObserver(ServiceRequestContext ctx) {
        return TestObserver.create(new Observer<Object>() {
            @Override
            public void onSubscribe(@NonNull Disposable d) {
                assertSameContext(ctx);
            }

            @Override
            public void onNext(@NonNull Object o) {
                assertSameContext(ctx);
            }

            @Override
            public void onError(@NonNull Throwable e) {
                assertSameContext(ctx);
            }

            @Override
            public void onComplete() {
                assertSameContext(ctx);
            }
        });
    }

    static TestSubscriber<Object> newTestSubscriber(ServiceRequestContext ctx) {
        return TestSubscriber.create(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {
                assertSameContext(ctx);
            }

            @Override
            public void onNext(@NonNull Object o) {
                assertSameContext(ctx);
            }

            @Override
            public void onError(@NonNull Throwable e) {
                assertSameContext(ctx);
            }

            @Override
            public void onComplete() {
                assertSameContext(ctx);
            }
        });
    }

    static Single<Object> newSingleWithoutCtx(Object input) {
        return Single.create(emitter -> {
            assertCurrentCtxIsNull();
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        });
    }

    static Single<Object> newSingle(Object input, ServiceRequestContext ctx) {
        return assertCtxInCallbacks(Single.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        }), ctx);
    }

    static Maybe<Object> newMaybeWithoutCtx(Object input) {
        return Maybe.create(emitter -> {
            assertCurrentCtxIsNull();
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        });
    }

    static Maybe<Object> newMaybe(Object input, ServiceRequestContext ctx) {
        return assertCtxInCallbacks(Maybe.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        }), ctx);
    }

    static Completable newCompletableWithoutCtx() {
        return Completable.create(emitter -> {
            assertCurrentCtxIsNull();
            ForkJoinPool.commonPool().execute(emitter::onComplete);
        });
    }

    static Completable newCompletable(ServiceRequestContext ctx) {
        return assertCtxInCallbacks(Completable.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(emitter::onComplete);
        }), ctx);
    }

    static <T> Flowable<T> newFlowableWithoutCtx(List<T> input) {
        return Flowable.create(emitter -> {
            assertCurrentCtxIsNull();
            ForkJoinPool.commonPool().execute(() -> {
                for (T value : input) {
                    emitter.onNext(value);
                }
                emitter.onComplete();
            });
        }, BackpressureStrategy.BUFFER);
    }

    static <T> Flowable<T> newFlowable(List<T> input, ServiceRequestContext ctx) {
        return assertCtxInCallbacks(Flowable.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(() -> {
                for (T value : input) {
                    emitter.onNext(value);
                }
                emitter.onComplete();
            });
        }, BackpressureStrategy.BUFFER), ctx);
    }

    static <T> Observable<T> newObservableWithoutCtx(List<T> input) {
        return Observable.create(emitter -> {
            assertCurrentCtxIsNull();
            ForkJoinPool.commonPool().execute(() -> {
                for (T value : input) {
                    emitter.onNext(value);
                }
                emitter.onComplete();
            });
        });
    }

    /**
     * This Flowable will generate entry by request size.
     */
    static <T> Flowable<T> newBackpressureAwareFlowable(List<T> input, ServiceRequestContext ctx) {
        return assertCtxInCallbacks(Flowable.generate(
                input::iterator,
                (iterator, emitter) -> {
                    assertSameContext(ctx);
                    if (iterator.hasNext()) {
                        emitter.onNext(iterator.next());
                    } else {
                        emitter.onComplete();
                    }
                }), ctx);
    }

    static <T> Observable<T> newObservable(List<T> input, ServiceRequestContext ctx) {
        return assertCtxInCallbacks(Observable.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(() -> {
                for (T value : input) {
                    emitter.onNext(value);
                }
                emitter.onComplete();
            });
        }), ctx);
    }

    static ServiceRequestContext newContext() {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                    .build();
    }

    static <T> Observable<T> assertCtxInCallbacks(Observable<T> observable, ServiceRequestContext ctx) {
        return observable.doOnSubscribe(s -> assertSameContext(ctx))
                         .doOnError(t -> assertSameContext(ctx))
                         .doFinally(() -> assertSameContext(ctx))
                         .doOnTerminate(() -> assertSameContext(ctx))
                         .doAfterTerminate(() -> assertSameContext(ctx))
                         .doOnComplete(() -> assertSameContext(ctx))
                         .doAfterNext(t -> assertSameContext(ctx))
                         .doOnEach(notification -> assertSameContext(ctx))
                         .doOnLifecycle(subscription -> assertSameContext(ctx),
                                        () -> assertSameContext(ctx))
                         .doOnNext(t -> assertSameContext(ctx));
    }

    static <T> Flowable<T> assertCtxInCallbacks(Flowable<T> flow, ServiceRequestContext ctx) {
        return flow.doOnSubscribe(s -> assertSameContext(ctx))
                   .doOnError(t -> assertSameContext(ctx))
                   .doFinally(() -> assertSameContext(ctx))
                   .doOnTerminate(() -> assertSameContext(ctx))
                   .doAfterTerminate(() -> assertSameContext(ctx))
                   .doOnComplete(() -> assertSameContext(ctx))
                   .doAfterNext(t -> assertSameContext(ctx))
                   .doOnCancel(() -> assertSameContext(ctx))
                   .doOnEach(notification -> assertSameContext(ctx))
                   .doOnRequest(t -> assertSameContext(ctx))
                   .doOnLifecycle(subscription -> assertSameContext(ctx),
                                  t -> assertSameContext(ctx),
                                  () -> assertSameContext(ctx))
                   .doOnNext(t -> assertSameContext(ctx));
    }

    @SuppressWarnings("UnstableApiUsage")
    static <T> Single<T> assertCtxInCallbacks(Single<T> single, ServiceRequestContext ctx) {
        return single.doOnSubscribe(s -> assertSameContext(ctx))
                     .doOnSuccess(t -> assertSameContext(ctx))
                     .doOnError(t -> assertSameContext(ctx))
                     .doAfterSuccess(t -> assertSameContext(ctx))
                     .doFinally(() -> assertSameContext(ctx))
                     // Experimental Api
                     .doOnTerminate(() -> assertSameContext(ctx))
                     .doOnDispose(() -> assertSameContext(ctx))
                     .doOnEvent((t, throwable) -> assertSameContext(ctx))
                     .doAfterTerminate(() -> assertSameContext(ctx));
    }

    @SuppressWarnings("UnstableApiUsage")
    static <T> Maybe<T> assertCtxInCallbacks(Maybe<T> maybe, ServiceRequestContext ctx) {
        return maybe.doOnSubscribe(s -> assertSameContext(ctx))
                    .doOnSuccess(t -> assertSameContext(ctx))
                    .doOnError(t -> assertSameContext(ctx))
                    .doAfterSuccess(t -> assertSameContext(ctx))
                    .doFinally(() -> assertSameContext(ctx))
                    // Experimental Api
                    .doOnTerminate(() -> assertSameContext(ctx))
                    .doOnDispose(() -> assertSameContext(ctx))
                    .doOnEvent((t, throwable) -> assertSameContext(ctx))
                    .doAfterTerminate(() -> assertSameContext(ctx))
                    .doOnComplete(() -> assertSameContext(ctx));
    }

    static <T> Completable assertCtxInCallbacks(Completable completable, ServiceRequestContext ctx) {
        return completable.doOnSubscribe(s -> assertSameContext(ctx))
                          .doOnError(t -> assertSameContext(ctx))
                          .doFinally(() -> assertSameContext(ctx))
                          .doOnTerminate(() -> assertSameContext(ctx))
                          .doOnDispose(() -> assertSameContext(ctx))
                          .doOnEvent(throwable -> assertSameContext(ctx))
                          .doAfterTerminate(() -> assertSameContext(ctx))
                          .doOnComplete(() -> assertSameContext(ctx));
    }

    static void assertSameContext(ServiceRequestContext ctx) {
        assertThat((RequestContext) RequestContext.currentOrNull()).isSameAs(ctx);
    }

    static void assertCurrentCtxIsNull() {
        assertThat((RequestContext) RequestContext.currentOrNull()).isNull();
    }

    static void assertCurrentCtxIsNotNull() {
        assertThat((RequestContext) RequestContext.currentOrNull()).isNotNull();
    }
}
