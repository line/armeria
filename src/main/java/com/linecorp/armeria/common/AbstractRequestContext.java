/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * A skeletal {@link RequestContext} implementation.
 */
public abstract class AbstractRequestContext implements RequestContext {

    @Override
    public final EventLoop contextAwareEventLoop() {
        return RequestContext.super.contextAwareEventLoop();
    }

    @Override
    public final Executor makeContextAware(Executor executor) {
        return RequestContext.super.makeContextAware(executor);
    }

    @Override
    public final <T> Callable<T> makeContextAware(Callable<T> callable) {
        return () -> {
            try (PushHandle ignored = propagateContextIfNotPresent()) {
                return callable.call();
            }
        };
    }

    @Override
    public final Runnable makeContextAware(Runnable runnable) {
        return () -> {
            try (PushHandle ignored = propagateContextIfNotPresent()) {
                runnable.run();
            }
        };
    }

    @Override
    public final <T> FutureListener<T> makeContextAware(FutureListener<T> listener) {
        return future -> invokeOperationComplete(listener, future);
    }

    @Override
    public final ChannelFutureListener makeContextAware(ChannelFutureListener listener) {
        return future -> invokeOperationComplete(listener, future);
    }

    @Override
    public final <T extends Future<?>> GenericFutureListener<T> makeContextAware(GenericFutureListener<T> listener) {
        return future -> invokeOperationComplete(listener, future);
    }

    private <T extends Future<?>> void invokeOperationComplete(
            GenericFutureListener<T> listener, T future) throws Exception {

        try (PushHandle ignored = propagateContextIfNotPresent()) {
            listener.operationComplete(future);
        }
    }

    private PushHandle propagateContextIfNotPresent() {
        return RequestContext.mapCurrent(currentContext -> {
            if (currentContext != this) {
                throw new IllegalStateException(
                        "Trying to call object made with makeContextAware or object on executor made with " +
                        "makeContextAware with context " + this +
                        ", but context is currently set to " + currentContext + ". This means the " +
                        "callback was passed from one invocation to another which is not allowed. Make " +
                        "sure you are not saving callbacks into shared state.");
            }
            return () -> {};
        }, () -> RequestContext.push(this, true));
    }

    @Override
    @Deprecated
    public final void resolvePromise(Promise<?> promise, Object result) {
        RequestContext.super.resolvePromise(promise, result);
    }

    @Override
    @Deprecated
    public final void rejectPromise(Promise<?> promise, Throwable cause) {
        RequestContext.super.rejectPromise(promise, cause);
    }

    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }
}
