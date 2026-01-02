/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.common.micrometer.context;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshot.Scope;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * This class works with the
 * <a href="https://docs.micrometer.io/context-propagation/reference/index.html">Micrometer
 * Context Propagation</a> to keep the {@link RequestContext} during
 * <a href="https://github.com/reactor/reactor-core">Reactor</a> operations.
 * Get the {@link RequestContextThreadLocalAccessor} to register it to the {@link ContextRegistry}.
 * Then, {@link ContextRegistry} will use {@link RequestContextThreadLocalAccessor} to
 * propagate context during the
 * <a href="https://github.com/reactor/reactor-core">Reactor</a> operations
 * so that you can get the context using {@link RequestContext#current()}.
 * However, please note that you should include Mono#contextWrite(ContextView) or
 * Flux#contextWrite(ContextView) to end of the Reactor codes.
 * If not, {@link RequestContext} will not be keep during Reactor Operation.
 */
@UnstableApi
public final class RequestContextThreadLocalAccessor implements ThreadLocalAccessor<RequestContext> {

    private static final Object KEY = RequestContext.class;

    /**
     * The value which obtained through {@link RequestContextThreadLocalAccessor},
     * will be stored in the Context under this {@code KEY}.
     * This method will be called by {@link ContextSnapshot} internally.
     */
    @Override
    public Object key() {
        return KEY;
    }

    /**
     * {@link ContextSnapshot} will call this method during the execution
     * of lambda functions in {@link ContextSnapshot#wrap(Runnable)},
     * as well as during Mono#subscribe(), Flux#subscribe(),
     * {@link Subscription#request(long)}, and CoreSubscriber#onSubscribe(Subscription).
     * Following these calls, {@link ContextSnapshot#setThreadLocals()} is
     * invoked to restore the state of {@link RequestContextStorage}.
     * Furthermore, at the end of these methods, {@link Scope#close()} is executed
     * to revert the {@link RequestContextStorage} to its original state.
     */
    @Nullable
    @Override
    public RequestContext getValue() {
        return RequestContext.currentOrNull();
    }

    /**
     * {@link ContextSnapshot} will call this method during the execution
     * of lambda functions in {@link ContextSnapshot#wrap(Runnable)},
     * as well as during Mono#subscribe(), Flux#subscribe(),
     * {@link Subscription#request(long)}, and CoreSubscriber#onSubscribe(Subscription).
     * Following these calls, {@link ContextSnapshot#setThreadLocals()} is
     * invoked to restore the state of {@link RequestContextStorage}.
     * Furthermore, at the end of these methods, {@link Scope#close()} is executed
     * to revert the {@link RequestContextStorage} to its original state.
     */
    @Override
    @SuppressWarnings("MustBeClosedChecker")
    public void setValue(RequestContext value) {
        RequestContextUtil.getAndSet(value);
    }

    /**
     * This method will be called at the start of {@link ContextSnapshot.Scope} and
     * the end of {@link ContextSnapshot.Scope}. If reactor Context does not
     * contains {@link RequestContextThreadLocalAccessor#KEY}, {@link ContextSnapshot} will use
     * this method to remove the value from {@link ThreadLocal}.
     * Please note that {@link RequestContextUtil#pop()} return {@link AutoCloseable} instance,
     * but it is not used in `Try with Resources` syntax. this is because {@link ContextSnapshot.Scope}
     * will handle the {@link AutoCloseable} instance returned by {@link RequestContextUtil#pop()}.
     */
    @Override
    @SuppressWarnings("MustBeClosedChecker")
    public void setValue() {
        RequestContextUtil.pop();
    }
}
