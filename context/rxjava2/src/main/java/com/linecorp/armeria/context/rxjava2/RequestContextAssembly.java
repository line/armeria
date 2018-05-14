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

package com.linecorp.armeria.context.rxjava2;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

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
    static final AtomicBoolean lock = new AtomicBoolean();

    private RequestContextAssembly() {}

    /**
     * Enable {@link RequestContext} during operators.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void enable() {
        if (!lock.compareAndSet(false, true)) {
            return;
        }

        RxJavaPlugins.setOnObservableAssembly(
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
                });

        RxJavaPlugins.setOnConnectableObservableAssembly(
                new ConditionalOnCurrentRequestContextFunction<ConnectableObservable>() {
                    @Override
                    ConnectableObservable applyActual(ConnectableObservable co, RequestContext ctx) {
                        return new RequestContextConnectableObservable(co, ctx);
                    }
                });

        RxJavaPlugins.setOnCompletableAssembly(
                new ConditionalOnCurrentRequestContextFunction<Completable>() {
                    @Override
                    Completable applyActual(Completable c, RequestContext ctx) {
                        if (!(c instanceof Callable)) {
                            return new RequestContextCompletable(c, ctx);
                        }
                        if (c instanceof ScalarCallable) {
                            return new RequestContextScalarCallableCompletable(c, ctx);
                        }
                        return new RequestContextCallableCompletable(c, ctx);
                    }
                });

        RxJavaPlugins.setOnSingleAssembly(new ConditionalOnCurrentRequestContextFunction<Single>() {
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
        });

        RxJavaPlugins.setOnMaybeAssembly(new ConditionalOnCurrentRequestContextFunction<Maybe>() {
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
        });

        RxJavaPlugins.setOnFlowableAssembly(new ConditionalOnCurrentRequestContextFunction<Flowable>() {
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
        });

        RxJavaPlugins.setOnConnectableFlowableAssembly(
                new ConditionalOnCurrentRequestContextFunction<ConnectableFlowable>() {
                    @Override
                    ConnectableFlowable applyActual(ConnectableFlowable cf, RequestContext ctx) {
                        return new RequestContextConnectableFlowable(cf, ctx);
                    }
                }
        );

        RxJavaPlugins.setOnParallelAssembly(
                new ConditionalOnCurrentRequestContextFunction<ParallelFlowable>() {
                    @Override
                    ParallelFlowable applyActual(ParallelFlowable pf, RequestContext ctx) {
                        return new RequestContextParallelFlowable(pf, ctx);
                    }
                });

        lock.set(false);
    }

    /**
     * Disable {@link RequestContext} during operators.
     */
    public static void disable() {
        if (!lock.compareAndSet(false, true)) {
            return;
        }

        RxJavaPlugins.setOnObservableAssembly(null);
        RxJavaPlugins.setOnConnectableObservableAssembly(null);
        RxJavaPlugins.setOnCompletableAssembly(null);
        RxJavaPlugins.setOnSingleAssembly(null);
        RxJavaPlugins.setOnMaybeAssembly(null);
        RxJavaPlugins.setOnFlowableAssembly(null);
        RxJavaPlugins.setOnConnectableFlowableAssembly(null);
        RxJavaPlugins.setOnParallelAssembly(null);

        lock.set(false);
    }

    abstract static class ConditionalOnCurrentRequestContextFunction<T> implements Function<T, T> {
        @Override
        public final T apply(T t) {
            //noinspection ConstantConditions
            return RequestContext.mapCurrent(requestContext -> applyActual(t, requestContext), () -> t);
        }

        abstract T applyActual(T t, RequestContext ctx);
    }
}
