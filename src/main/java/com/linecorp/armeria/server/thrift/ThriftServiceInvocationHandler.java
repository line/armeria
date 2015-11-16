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

package com.linecorp.armeria.server.thrift;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.util.concurrent.Promise;

final class ThriftServiceInvocationHandler implements ServiceInvocationHandler {

    private final Object service;

    ThriftServiceInvocationHandler(Object service) {
        this.service = requireNonNull(service, "service");
    }

    @Override
    public void invoke(ServiceInvocationContext ctx,
                       Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {
        final ThriftServiceInvocationContext tCtx = (ThriftServiceInvocationContext) ctx;
        final ThriftFunction f = tCtx.func;

        if (f.isAsync()) {
            invokeAsynchronously(tCtx, promise);
        } else {
            invokeSynchronously(tCtx, blockingTaskExecutor, promise);
        }
    }

    private void invokeAsynchronously(ThriftServiceInvocationContext ctx, Promise<Object> promise) {
        final ThriftFunction func = ctx.func;
        final AsyncProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>, Object> f = func.asyncFunc();

        try {
            f.start(service, ctx.args, new AsyncMethodCallback<Object>() {
                @Override
                public void onComplete(Object response) {
                    if (func.isOneway()) {
                        safeSetSuccess(ctx, promise, null);
                        return;
                    }

                    try {
                        TBase<TBase<?, ?>, TFieldIdEnum> result = func.newResult();
                        func.setSuccess(result, response);
                        safeSetSuccess(ctx, promise, result);
                    } catch (Throwable t) {
                        safeSetFailure(ctx, promise, t);
                    }
                }

                @Override
                public void onError(Exception e) {
                    safeSetFailure(ctx, promise, e);
                }
            });
        } catch (Throwable t) {
            safeSetFailure(ctx, promise, t);
        }
    }

    private void invokeSynchronously(ThriftServiceInvocationContext ctx,
                                     Executor blockingTaskExecutor, Promise<Object> promise) {

        final ThriftFunction func = ctx.func;
        final ProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>> f = func.syncFunc();
        try {
            blockingTaskExecutor.execute(() -> {
                if (promise.isDone()) {
                    ctx.logger().warn("Promise is done already; not invoking: {}", promise);
                    return;
                }

                ServiceInvocationContext.setCurrent(ctx);
                try {
                    @SuppressWarnings("unchecked")
                    TBase<TBase<?, ?>, TFieldIdEnum> result = f.getResult(service, ctx.args);
                    if (func.isOneway()) {
                        result = null;
                    }

                    safeSetSuccess(ctx, promise, result);
                } catch (Throwable t) {
                    safeSetFailure(ctx, promise, t);
                } finally {
                    ServiceInvocationContext.removeCurrent();
                }
            });
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    private static void safeSetSuccess(
            ThriftServiceInvocationContext ctx, Promise<Object> promise, TBase<TBase<?, ?>, TFieldIdEnum> result) {

        if (!promise.trySuccess(result)) {
            ctx.logger().warn("Failed to notify a result ({}) to a promise ({})", result, promise);
        }
    }

    private static void safeSetFailure(ThriftServiceInvocationContext ctx, Promise<Object> promise, Throwable t) {
        if (!promise.tryFailure(t)) {
            ctx.logger().warn("Failed to notify a failure ({}) to a promise ({})", t, promise, t);
        }
    }
}
