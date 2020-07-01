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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericProgressiveFutureListener;
import io.netty.util.concurrent.ProgressiveFuture;

@SuppressWarnings("rawtypes")
final class ContextAwareProgressiveFutureListener implements GenericProgressiveFutureListener {

    @SuppressWarnings("unchecked")
    static <T extends ProgressiveFuture<?>> GenericProgressiveFutureListener<T> of(
            RequestContext ctx, GenericProgressiveFutureListener listener) {
        requireNonNull(ctx, "ctx");
        requireNonNull(listener, "listener");
        return new ContextAwareProgressiveFutureListener(ctx, listener);
    }

    private final RequestContext ctx;
    private final GenericProgressiveFutureListener listener;

    private ContextAwareProgressiveFutureListener(RequestContext ctx,
                                                  GenericProgressiveFutureListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void operationProgressed(ProgressiveFuture future, long progress, long total)
            throws Exception {
        try (SafeCloseable ignored = ctx.push()) {
            listener.operationProgressed(future, progress, total);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void operationComplete(Future future) throws Exception {
        try (SafeCloseable ignored = ctx.push()) {
            listener.operationComplete(future);
        }
    }
}
