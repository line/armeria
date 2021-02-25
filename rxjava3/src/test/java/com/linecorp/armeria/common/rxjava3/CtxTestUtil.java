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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ForkJoinPool;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subscribers.TestSubscriber;

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
            public void onNext(Object o) {
                assertSameContext(ctx);
            }

            @Override
            public void onError(Throwable t) {
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
        return Single.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        });
    }

    static Flowable<Object> newFlowableWithoutCtx(Object input, int createCount) {
        return Flowable.create(emitter -> {
            assertCurrentCtxIsNull();
            ForkJoinPool.commonPool().execute(() -> {
                for (int i = 0; i < createCount; i++) {
                    emitter.onNext(input);
                }
                emitter.onComplete();
            });
        }, BackpressureStrategy.BUFFER);
    }

    static Flowable<Object> newFlowable(Object input, int emitCount, ServiceRequestContext ctx) {
        return Flowable.create(emitter -> {
            assertSameContext(ctx);
            ForkJoinPool.commonPool().execute(() -> {
                for (int i = 0; i < emitCount; i++) {
                    emitter.onNext(input);
                }
                emitter.onComplete();
            });
        }, BackpressureStrategy.BUFFER);
    }

    static ServiceRequestContext newContext() {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                    .build();
    }

    static <T> Single<T> addCallbacks(Single<T> single, ServiceRequestContext ctx) {
        return single.doOnSubscribe(s -> assertSameContext(ctx))
                     .doOnSuccess(t -> assertSameContext(ctx))
                     .doOnError(t -> assertSameContext(ctx))
                     .doAfterSuccess(t -> assertSameContext(ctx))
                     .doFinally(() -> assertSameContext(ctx))
                     .doOnDispose(() -> assertSameContext(ctx))
                     .doOnEvent((t, throwable) -> assertSameContext(ctx))
                     .doAfterTerminate(() -> assertSameContext(ctx));
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
