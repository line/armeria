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

import java.util.concurrent.ForkJoinPool;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
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
                assertThat(ctxExists(ctx)).isTrue();
            }

            @Override
            public void onNext(@NonNull Object o) {
                assertThat(ctxExists(ctx)).isTrue();
            }

            @Override
            public void onError(@NonNull Throwable e) {
                assertThat(ctxExists(ctx)).isTrue();
            }

            @Override
            public void onComplete() {
                assertThat(ctxExists(ctx)).isTrue();
            }
        });
    }

    static TestSubscriber<Object> newTestSubscriber(ServiceRequestContext ctx) {
        return TestSubscriber.create(new Subscriber<Object>() {

            @Override
            public void onSubscribe(Subscription s) {
                assertThat(ctxExists(ctx)).isTrue();
            }

            @Override
            public void onNext(@NonNull Object o) {
                assertThat(ctxExists(ctx)).isTrue();
            }

            @Override
            public void onError(@NonNull Throwable e) {
                assertThat(ctxExists(ctx)).isTrue();
            }

            @Override
            public void onComplete() {
                assertThat(ctxExists(ctx)).isTrue();
            }
        });
    }

    static Single<Object> newSingleWithoutCtx(Object input) {
        return Single.create(emitter -> {
            assertThat(currentCtx()).isNull();
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        });
    }

    static Single<Object> newSingle(Object input, ServiceRequestContext ctx) {
        return Single.create(emitter -> {
            assertThat(ctxExists(ctx)).isTrue();
            ForkJoinPool.commonPool().execute(() -> emitter.onSuccess(input));
        });
    }

    static Flowable<Object> newFlowableWithoutCtx(Object input, int createCount) {
        return Flowable.create(emitter -> {
            assertThat(currentCtx()).isNull();
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
            assertThat(ctxExists(ctx)).isTrue();
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

    static boolean ctxExists(ServiceRequestContext ctx) {
        return RequestContext.currentOrNull() == ctx;
    }

    static <T> Single<T> addCallbacks(Single<T> single, ServiceRequestContext ctx) {
        return single.doOnSubscribe(s -> assertThat(ctxExists(ctx)).isTrue())
                     .doOnSuccess(t -> assertThat(ctxExists(ctx)).isTrue())
                     .doOnError(t -> assertThat(ctxExists(ctx)).isTrue())
                     .doAfterSuccess(t -> assertThat(ctxExists(ctx)).isTrue())
                     .doFinally(() -> assertThat(ctxExists(ctx)).isTrue())
                     .doOnDispose(() -> assertThat(ctxExists(ctx)).isTrue())
                     .doOnEvent((t, throwable) -> assertThat(ctxExists(ctx)).isTrue())
                     .doAfterTerminate(() -> assertThat(ctxExists(ctx)).isTrue());
    }

    @Nullable
    static RequestContext currentCtx() {
        return RequestContext.currentOrNull();
    }
}
