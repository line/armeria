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
package com.linecorp.armeria.common;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GenericProgressiveFutureListener;

@SuppressWarnings("rawtypes")
final class ContextAwareFutureListener implements GenericFutureListener {

    @SuppressWarnings("unchecked")
    static <T extends Future<?>> GenericFutureListener<T> of(
            RequestContext ctx, GenericFutureListener listener) {

        if (listener instanceof GenericProgressiveFutureListener) {
            return (GenericFutureListener) ContextAwareProgressiveFutureListener.of(
                    ctx, (GenericProgressiveFutureListener) listener);
        }

        return new ContextAwareFutureListener(ctx, listener);
    }

    private final RequestContext ctx;
    private final GenericFutureListener listener;

    private ContextAwareFutureListener(RequestContext ctx, GenericFutureListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void operationComplete(Future future) throws Exception {
        try (SafeCloseable ignored = ctx.push()) {
            listener.operationComplete(future);
        }
    }
}
