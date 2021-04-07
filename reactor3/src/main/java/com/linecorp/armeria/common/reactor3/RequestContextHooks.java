/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.reactor3;

import java.util.function.Consumer;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.ContextHolder;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.util.SafeCloseable;

import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Fuseable;
import reactor.core.Fuseable.ScalarCallable;
import reactor.core.Scannable;
import reactor.core.Scannable.Attr;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ParallelFlux;
import reactor.util.context.Context;

/**
 * Utility class to keep {@link RequestContext} during
 * <a href="https://github.com/reactor/reactor-core">Reactor</a> operations.
 */
public final class RequestContextHooks {

    private static final Logger logger = LoggerFactory.getLogger(RequestContextHooks.class);

    private static final String ON_EACH_OPERATOR_HOOK_KEY =
            RequestContextHooks.class.getName() + "#ON_EACH_OPERATOR_HOOK_KEY";

    private static final String ON_LAST_OPERATOR_HOOK_KEY =
            RequestContextHooks.class.getName() + "#ON_LAST_OPERATOR_HOOK_KEY";

    private static boolean warnedParallelFluxUnsupported;

    private static boolean enabled;

    private static final String FLUX_ERROR_SUPPLIED = "reactor.core.publisher.FluxErrorSupplied";
    private static final String MONO_ERROR_SUPPLIED = "reactor.core.publisher.MonoErrorSupplied";

    /**
     * Enables {@link RequestContext} during Reactor operations.
     * The reactor {@link Publisher}s such as {@link Mono} and {@link Flux} will have the
     * {@link RequestContext} which is in the {@link RequestContextStorage} when the {@link Publisher}s
     * are created. Then, the {@link RequestContext} is propagated during the
     * operations so that you can get the context using {@link RequestContext#current()}.
     *
     * <p>However, please note that {@link Mono#doOnCancel(Runnable)}, {@link Mono#doFinally(Consumer)},
     * {@link Flux#doOnCancel(Runnable)} and {@link Flux#doFinally(Consumer)} will not propagate the context.
     *
     * <p>Also, note that this method does not have any relevance to Reactor's own {@link Context} API.
     */
    public static synchronized void enable() {
        if (enabled) {
            return;
        }
        Hooks.onEachOperator(ON_EACH_OPERATOR_HOOK_KEY, source -> {
            if (source instanceof ContextHolder || isReproducibleScalarType(source)) {
                return source;
            }

            if (source instanceof Scannable) {
                final Object parent = ((Scannable) source).scanUnsafe(Attr.PARENT);
                if (parent instanceof ContextHolder) {
                    return makeContextAware(source, ((ContextHolder) parent).context());
                }
            }
            return RequestContext.mapCurrent(requestContext -> makeContextAware(source, requestContext),
                                             () -> source);
        });

        Hooks.onLastOperator(ON_LAST_OPERATOR_HOOK_KEY, source -> {
            if (source instanceof ContextHolder || isReproducibleScalarType(source)) {
                return source;
            }

            if (source instanceof Scannable) {
                final Object parent = ((Scannable) source).scanUnsafe(Attr.PARENT);
                if (parent instanceof ContextHolder) {
                    return makeContextAware(source, ((ContextHolder) parent).context());
                }
            }
            return source;
        });

        enabled = true;
    }

    /**
     * Disables {@link RequestContext} during Reactor operations.
     */
    public static synchronized void disable() {
        if (!enabled) {
            return;
        }
        Hooks.resetOnEachOperator(ON_EACH_OPERATOR_HOOK_KEY);
        Hooks.resetOnLastOperator(ON_LAST_OPERATOR_HOOK_KEY);
        enabled = false;
    }

    /**
     * Returns whether the specified {@link Publisher} is reproducible {@link Fuseable.ScalarCallable} such as
     * {@link Flux#empty()}, {@link Flux#just(Object)}, {@link Flux#error(Throwable)},
     * {@link Mono#empty()}, {@link Mono#just(Object)} and {@link Mono#error(Throwable)}.
     */
    private static boolean isReproducibleScalarType(Publisher<Object> publisher) {
        if (publisher instanceof ScalarCallable) {
            final String className = publisher.getClass().getName();
            return !className.equals(FLUX_ERROR_SUPPLIED) && !className.equals(MONO_ERROR_SUPPLIED);
        }
        return false;
    }

    private static Publisher<Object> makeContextAware(Publisher<Object> source, RequestContext ctx) {
        if (source instanceof Mono) {
            return new ContextAwareMono((Mono<Object>) source, ctx);
        }
        if (source instanceof ConnectableFlux) {
            return new ContextAwareConnectableFlux((ConnectableFlux<Object>) source, ctx);
        }
        if (source instanceof ParallelFlux) {
            // TODO(minwoox) Support ParallelFlux after
            //               https://github.com/reactor/reactor-core/issues/2328 is addressed.
            if (!warnedParallelFluxUnsupported) {
                warnedParallelFluxUnsupported = true;
                logger.warn("Hooks for {} are not supported yet.", ParallelFlux.class.getSimpleName());
            }
            return source;
        }
        if (source instanceof Flux) {
            return new ContextAwareFlux((Flux<Object>) source, ctx);
        }
        return source;
    }

    private RequestContextHooks() {}

    @VisibleForTesting
    static final class ContextAwareMono extends Mono<Object> implements ContextHolder {

        private final Mono<Object> source;
        private final RequestContext ctx;

        ContextAwareMono(Mono<Object> source, RequestContext ctx) {
            this.source = source;
            this.ctx = ctx;
        }

        @Override
        public RequestContext context() {
            return ctx;
        }

        @Override
        public void subscribe(CoreSubscriber<? super Object> subscriber) {
            try (SafeCloseable ignored = ctx.push()) {
                if (subscriber instanceof ContextAwareCoreSubscriber) {
                    source.subscribe(subscriber);
                } else {
                    source.subscribe(new ContextAwareCoreSubscriber(subscriber, ctx));
                }
            }
        }
    }

    @VisibleForTesting
    static final class ContextAwareFlux extends Flux<Object> implements ContextHolder {

        private final Flux<Object> source;
        private final RequestContext ctx;

        ContextAwareFlux(Flux<Object> source, RequestContext ctx) {
            this.source = source;
            this.ctx = ctx;
        }

        @Override
        public RequestContext context() {
            return ctx;
        }

        @Override
        public void subscribe(CoreSubscriber<? super Object> subscriber) {
            try (SafeCloseable ignored = ctx.push()) {
                if (subscriber instanceof ContextAwareCoreSubscriber) {
                    source.subscribe(subscriber);
                } else {
                    source.subscribe(new ContextAwareCoreSubscriber(subscriber, ctx));
                }
            }
        }
    }

    private static final class ContextAwareConnectableFlux extends ConnectableFlux<Object>
            implements ContextHolder {

        private final ConnectableFlux<Object> source;
        private final RequestContext ctx;

        ContextAwareConnectableFlux(ConnectableFlux<Object> source, RequestContext ctx) {
            this.source = source;
            this.ctx = ctx;
        }

        @Override
        public RequestContext context() {
            return ctx;
        }

        @Override
        public void connect(Consumer<? super Disposable> cancelSupport) {
            try (SafeCloseable ignored = ctx.push()) {
                source.connect(cancelSupport);
            }
        }

        @Override
        public void subscribe(CoreSubscriber<? super Object> subscriber) {
            try (SafeCloseable ignored = ctx.push()) {
                if (subscriber instanceof ContextAwareCoreSubscriber) {
                    source.subscribe(subscriber);
                } else {
                    source.subscribe(new ContextAwareCoreSubscriber(subscriber, ctx));
                }
            }
        }
    }

    private static final class ContextAwareCoreSubscriber implements CoreSubscriber<Object> {

        private final CoreSubscriber<? super Object> subscriber;
        private final RequestContext ctx;

        ContextAwareCoreSubscriber(CoreSubscriber<? super Object> subscriber, RequestContext ctx) {
            this.subscriber = subscriber;
            this.ctx = ctx;
        }

        @Override
        public Context currentContext() {
            return subscriber.currentContext();
        }

        @Override
        public void onSubscribe(Subscription s) {
            try (SafeCloseable ignored = ctx.push()) {
                subscriber.onSubscribe(s);
            }
        }

        @Override
        public void onNext(Object o) {
            try (SafeCloseable ignored = ctx.push()) {
                subscriber.onNext(o);
            }
        }

        @Override
        public void onError(Throwable t) {
            try (SafeCloseable ignored = ctx.push()) {
                subscriber.onError(t);
            }
        }

        @Override
        public void onComplete() {
            try (SafeCloseable ignored = ctx.push()) {
                subscriber.onComplete();
            }
        }
    }
}
