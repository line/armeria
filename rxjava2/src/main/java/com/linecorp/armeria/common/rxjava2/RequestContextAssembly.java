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

import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.RequestContext;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.flowables.ConnectableFlowable;
import io.reactivex.functions.Function;
import io.reactivex.internal.fuseable.ScalarCallable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.parallel.ParallelFlowable;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Utility class to keep {@link RequestContext} during RxJava operations.
 */
public final class RequestContextAssembly {

    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super Observable, ? extends Observable> oldOnObservableAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super ConnectableObservable, ? extends ConnectableObservable>
            oldOnConnectableObservableAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super Completable, ? extends Completable> oldOnCompletableAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super Single, ? extends Single> oldOnSingleAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super Maybe, ? extends Maybe> oldOnMaybeAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super Flowable, ? extends Flowable> oldOnFlowableAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super ConnectableFlowable, ? extends ConnectableFlowable>
            oldOnConnectableFlowableAssembly;
    @SuppressWarnings("rawtypes")
    @Nullable
    @GuardedBy("RequestContextAssembly.class")
    private static Function<? super ParallelFlowable, ? extends ParallelFlowable> oldOnParallelAssembly;

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

        oldOnObservableAssembly = RxJavaPlugins.getOnObservableAssembly();
        RxJavaPlugins.setOnObservableAssembly(compose(
                oldOnObservableAssembly,
                new ConditionalOnCurrentRequestContextFunction<Observable>() {
                    @Override
                    Observable applyActual(Observable o, RequestContext ctx) {
                        if (!(o instanceof Callable)) {
                            return new RequestContextObservable(o, ctx);
                        }
                        if (o instanceof ScalarCallable) {
                            return new RequestContextScalarCallableObservable(o, ctx);
                        }
                        return new RequestContextCallableObservable(o, ctx);
                    }
                }));

        oldOnConnectableObservableAssembly = RxJavaPlugins.getOnConnectableObservableAssembly();
        RxJavaPlugins.setOnConnectableObservableAssembly(compose(
                oldOnConnectableObservableAssembly,
                new ConditionalOnCurrentRequestContextFunction<ConnectableObservable>() {
                    @Override
                    ConnectableObservable applyActual(ConnectableObservable co, RequestContext ctx) {
                        return new RequestContextConnectableObservable(co, ctx);
                    }
                }));

        oldOnCompletableAssembly = RxJavaPlugins.getOnCompletableAssembly();
        RxJavaPlugins.setOnCompletableAssembly(
                compose(oldOnCompletableAssembly,
                        new ConditionalOnCurrentRequestContextFunction<Completable>() {
                            @Override
                            Completable applyActual(Completable c,
                                                    RequestContext ctx) {
                                if (!(c instanceof Callable)) {
                                    return new RequestContextCompletable(c, ctx);
                                }
                                if (c instanceof ScalarCallable) {
                                    return new RequestContextScalarCallableCompletable(c, ctx);
                                }
                                return new RequestContextCallableCompletable(c, ctx);
                            }
                        }));

        oldOnSingleAssembly = RxJavaPlugins.getOnSingleAssembly();
        RxJavaPlugins.setOnSingleAssembly(
                compose(oldOnSingleAssembly, new ConditionalOnCurrentRequestContextFunction<Single>() {
                    @Override
                    Single applyActual(Single s, RequestContext ctx) {
                        if (!(s instanceof Callable)) {
                            return new RequestContextSingle(s, ctx);
                        }
                        if (s instanceof ScalarCallable) {
                            return new RequestContextScalarCallableSingle(s, ctx);
                        }
                        return new RequestContextCallableSingle(s, ctx);
                    }
                }));

        oldOnMaybeAssembly = RxJavaPlugins.getOnMaybeAssembly();
        RxJavaPlugins.setOnMaybeAssembly(
                compose(oldOnMaybeAssembly, new ConditionalOnCurrentRequestContextFunction<Maybe>() {
                    @Override
                    Maybe applyActual(Maybe m, RequestContext ctx) {
                        if (!(m instanceof Callable)) {
                            return new RequestContextMaybe(m, ctx);
                        }
                        if (m instanceof ScalarCallable) {
                            return new RequestContextScalarCallableMaybe(m, ctx);
                        }
                        return new RequestContextCallableMaybe(m, ctx);
                    }
                }));

        oldOnFlowableAssembly = RxJavaPlugins.getOnFlowableAssembly();
        RxJavaPlugins.setOnFlowableAssembly(
                compose(oldOnFlowableAssembly, new ConditionalOnCurrentRequestContextFunction<Flowable>() {
                    @Override
                    Flowable applyActual(Flowable f, RequestContext ctx) {
                        if (!(f instanceof Callable)) {
                            return new RequestContextFlowable(f, ctx);
                        }
                        if (f instanceof ScalarCallable) {
                            return new RequestContextScalarCallableFlowable(f, ctx);
                        }
                        return new RequestContextCallableFlowable(f, ctx);
                    }
                }));

        oldOnConnectableFlowableAssembly = RxJavaPlugins.getOnConnectableFlowableAssembly();
        RxJavaPlugins.setOnConnectableFlowableAssembly(
                compose(oldOnConnectableFlowableAssembly,
                        new ConditionalOnCurrentRequestContextFunction<ConnectableFlowable>() {
                            @Override
                            ConnectableFlowable applyActual(
                                    ConnectableFlowable cf,
                                    RequestContext ctx) {
                                return new RequestContextConnectableFlowable(cf, ctx);
                            }
                        }
                ));

        oldOnParallelAssembly = RxJavaPlugins.getOnParallelAssembly();
        RxJavaPlugins.setOnParallelAssembly(
                compose(oldOnParallelAssembly,
                        new ConditionalOnCurrentRequestContextFunction<ParallelFlowable>() {
                            @Override
                            ParallelFlowable applyActual(ParallelFlowable pf, RequestContext ctx) {
                                return new RequestContextParallelFlowable(pf, ctx);
                            }
                        }
                ));
        enabled = true;
    }

    /**
     * Disable {@link RequestContext} during operators.
     */
    public static synchronized void disable() {
        if (!enabled) {
            return;
        }
        RxJavaPlugins.setOnObservableAssembly(oldOnObservableAssembly);
        oldOnObservableAssembly = null;
        RxJavaPlugins.setOnConnectableObservableAssembly(oldOnConnectableObservableAssembly);
        oldOnConnectableObservableAssembly = null;
        RxJavaPlugins.setOnCompletableAssembly(oldOnCompletableAssembly);
        oldOnCompletableAssembly = null;
        RxJavaPlugins.setOnSingleAssembly(oldOnSingleAssembly);
        oldOnSingleAssembly = null;
        RxJavaPlugins.setOnMaybeAssembly(oldOnMaybeAssembly);
        oldOnMaybeAssembly = null;
        RxJavaPlugins.setOnFlowableAssembly(oldOnFlowableAssembly);
        oldOnFlowableAssembly = null;
        RxJavaPlugins.setOnConnectableFlowableAssembly(oldOnConnectableFlowableAssembly);
        oldOnConnectableFlowableAssembly = null;
        RxJavaPlugins.setOnParallelAssembly(oldOnParallelAssembly);
        oldOnParallelAssembly = null;
        enabled = false;
    }

    private abstract static class ConditionalOnCurrentRequestContextFunction<T> implements Function<T, T> {
        @Override
        public final T apply(T t) {
            return RequestContext.mapCurrent(requestContext -> applyActual(t, requestContext), () -> t);
        }

        abstract T applyActual(T t, RequestContext ctx);
    }

    private static <T> Function<? super T, ? extends T> compose(
            @Nullable Function<? super T, ? extends T> before,
            Function<? super T, ? extends T> after) {
        if (before == null) {
            return after;
        }
        return (T v) -> after.apply(before.apply(v));
    }
}
