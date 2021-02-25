/*
 * Copyright 2018 LINE Corporation
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

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.Completable;
import io.reactivex.CompletableObserver;
import io.reactivex.Flowable;
import io.reactivex.FlowableSubscriber;
import io.reactivex.Maybe;
import io.reactivex.MaybeObserver;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.functions.BiFunction;
import io.reactivex.internal.fuseable.ConditionalSubscriber;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Utility class to keep {@link RequestContext} during RxJava operations.
 * This plugin doesn't support Flowable#parallel.
 * https://github.com/ReactiveX/RxJava/issues/7190
 */
public final class RequestContextAssembly {
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static BiFunction<? super Observable, ? super Observer, ? extends Observer>
            oldOnObservableSubscribe;
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static BiFunction<? super Completable, ? super CompletableObserver, ? extends CompletableObserver>
            oldOnCompletableSubscribe;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static BiFunction<? super Single, ? super SingleObserver, ? extends SingleObserver>
            oldOnSingleSubscribe;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static BiFunction<? super Maybe, ? super MaybeObserver, ? extends MaybeObserver>
            oldOnMaybeSubscribe;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static BiFunction<? super Flowable, ? super Subscriber, ? extends Subscriber>
            oldOnFlowableSubscribe;

    @GuardedBy("RequestContextAssembly.class")
    private static boolean enabled;

    private RequestContextAssembly() {
    }

    /**
     * Enable {@link RequestContext} during operators.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static synchronized void enable() {
        if (enabled) {
            return;
        }

        oldOnObservableSubscribe = RxJavaPlugins.getOnObservableSubscribe();
        RxJavaPlugins.setOnObservableSubscribe(biCompose(
                oldOnObservableSubscribe,
                new BiConditionalOnCurrentRequestContextFunction<Observable, Observer>() {
                    @Override
                    Observer applyActual(Observable observable, Observer observer, RequestContext ctx) {
                        try (SafeCloseable ignored = ctx.push()) {
                            return new RequestContextObserver<>(observer, ctx);
                        }
                    }
                }
        ));

        oldOnCompletableSubscribe = RxJavaPlugins.getOnCompletableSubscribe();
        RxJavaPlugins.setOnCompletableSubscribe(biCompose(
                oldOnCompletableSubscribe,
                new BiConditionalOnCurrentRequestContextFunction<Completable, CompletableObserver>() {
                    @Override
                    CompletableObserver applyActual(Completable completable, CompletableObserver observer,
                                                    RequestContext ctx) {
                        try (SafeCloseable ignored = ctx.push()) {
                            return new RequestContextCompletableObserver(observer, ctx);
                        }
                    }
                }));

        oldOnSingleSubscribe = RxJavaPlugins.getOnSingleSubscribe();
        RxJavaPlugins.setOnSingleSubscribe(biCompose(
                oldOnSingleSubscribe,
                new BiConditionalOnCurrentRequestContextFunction<Single, SingleObserver>() {
                    @Override
                    SingleObserver applyActual(Single single, SingleObserver observer,
                                               RequestContext ctx) {
                        try (SafeCloseable ignored = ctx.push()) {
                            return new RequestContextSingleObserver<>(observer, ctx);
                        }
                    }
                }));

        oldOnMaybeSubscribe = RxJavaPlugins.getOnMaybeSubscribe();
        RxJavaPlugins.setOnMaybeSubscribe(
                (BiFunction<? super Maybe, MaybeObserver, ? extends MaybeObserver>) biCompose(
                        oldOnMaybeSubscribe,
                        new BiConditionalOnCurrentRequestContextFunction<Maybe, MaybeObserver>() {
                            @Override
                            MaybeObserver applyActual(Maybe maybe, MaybeObserver observer,
                                                      RequestContext ctx) {
                                try (SafeCloseable ignored = ctx.push()) {
                                    return new RequestContextMaybeObserver(observer, ctx);
                                }
                            }
                        }
                ));

        oldOnFlowableSubscribe = RxJavaPlugins.getOnFlowableSubscribe();
        RxJavaPlugins.setOnFlowableSubscribe(biCompose(
                oldOnFlowableSubscribe,
                new BiConditionalOnCurrentRequestContextFunction<Flowable, Subscriber>() {
                    @Override
                    FlowableSubscriber applyActual(Flowable flowable, Subscriber subscriber,
                                                   RequestContext ctx) {
                        try (SafeCloseable ignored = ctx.push()) {
                            if (subscriber instanceof ConditionalSubscriber) {
                                return new RequestContextConditionalSubscriber(
                                        (ConditionalSubscriber) subscriber, ctx);
                            }
                            return new RequestContextSubscriber(subscriber, ctx);
                        }
                    }
                }
        ));
        enabled = true;
    }

    /**
     * Disable {@link RequestContext} during operators.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static synchronized void disable() {
        if (!enabled) {
            return;
        }
        RxJavaPlugins.setOnObservableSubscribe(oldOnObservableSubscribe);
        oldOnObservableSubscribe = null;
        RxJavaPlugins.setOnCompletableSubscribe(oldOnCompletableSubscribe);
        oldOnCompletableSubscribe = null;
        RxJavaPlugins.setOnMaybeSubscribe(
                (BiFunction<? super Maybe, MaybeObserver, ? extends MaybeObserver>) oldOnMaybeSubscribe);
        oldOnMaybeSubscribe = null;
        RxJavaPlugins.setOnSingleSubscribe(oldOnSingleSubscribe);
        oldOnSingleSubscribe = null;
        RxJavaPlugins.setOnFlowableSubscribe(oldOnFlowableSubscribe);
        oldOnFlowableSubscribe = null;

        enabled = false;
    }

    private abstract static class BiConditionalOnCurrentRequestContextFunction<T, U>
            implements BiFunction<T, U, U> {
        @SuppressWarnings("ConstantConditions")
        @Override
        public final U apply(T t, U u) {
            return RequestContext.mapCurrent(requestContext -> applyActual(t, u, requestContext), () -> u);
        }

        abstract U applyActual(T t, U u, RequestContext ctx);
    }

    private static <T, U> BiFunction<? super T, ? super U, ? extends U> biCompose(
            @Nullable BiFunction<? super T, ? super U, ? extends U> before,
            BiFunction<? super T, ? super U, ? extends U> after) {
        if (before == null) {
            return after;
        }
        return (T v, U u) -> after.apply(v, before.apply(v, u));
    }
}
