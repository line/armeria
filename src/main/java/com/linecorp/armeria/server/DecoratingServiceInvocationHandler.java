/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.linecorp.armeria.common.ServiceInvocationContext;

import io.netty.util.concurrent.Promise;

/**
 * A {@link ServiceInvocationHandler} that decorates another {@link ServiceInvocationHandler}. Do not use this
 * class unless you want to define a new dedicated {@link ServiceInvocationHandler} type by extending this
 * class; prefer:
 * <ul>
 *   <li>{@link Service#decorate(Function)}</li>
 *   <li>{@link Service#decorateHandler(Function)}</li>
 *   <li>{@link Service#newDecorator(Function, Function)}</li>
 * </ul>
 */
public abstract class DecoratingServiceInvocationHandler implements ServiceInvocationHandler {

    private final ServiceInvocationHandler handler;

    /**
     * Creates a new instance that decorates the specified {@link ServiceInvocationHandler}.
     */
    protected DecoratingServiceInvocationHandler(ServiceInvocationHandler handler) {
        this.handler = requireNonNull(handler, "handler");
    }

    /**
     * Returns the {@link ServiceInvocationHandler} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends ServiceInvocationHandler> T delegate() {
        return (T) handler;
    }

    @Override
    public void handlerAdded(ServiceConfig cfg) throws Exception {
        ServiceCallbackInvoker.invokeHandlerAdded(cfg, delegate());
    }

    @Override
    public void invoke(ServiceInvocationContext ctx,
                       Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {

        delegate().invoke(ctx, blockingTaskExecutor, promise);
    }

    @Override
    public final <T extends ServiceInvocationHandler> Optional<T> as(Class<T> handlerType) {
        final Optional<T> result = ServiceInvocationHandler.super.as(handlerType);
        return result.isPresent() ? result : delegate().as(handlerType);
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate() + ')';
    }
}
