/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

/**
 * An {@link RpcResponse} implementation which is initially incomplete and can be completed later.
 */
public final class CompletableRpcResponse extends EventLoopCheckingFuture<Object> implements RpcResponse {

    private static final AtomicReferenceFieldUpdater<CompletableRpcResponse, Throwable> causeUpdater =
            AtomicReferenceFieldUpdater.newUpdater(CompletableRpcResponse.class, Throwable.class, "cause");

    @Nullable
    private volatile Throwable cause;

    @Nullable
    @Override
    public Throwable cause() {
        return cause;
    }

    @Override
    public boolean completeExceptionally(Throwable cause) {
        if (causeUpdater.compareAndSet(this, null, requireNonNull(cause, "cause"))) {
            return super.completeExceptionally(cause);
        }
        return false;
    }

    @Override
    public void obtrudeException(Throwable cause) {
        this.cause = requireNonNull(cause, "cause");
        super.obtrudeException(cause);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        final boolean updated = !isDone() && completeExceptionally(new CancellationException());
        return updated || isCancelled();
    }

    @Override
    public String toString() {
        if (isDone()) {
            if (isCompletedExceptionally()) {
                return MoreObjects.toStringHelper(this)
                                  .add("cause", cause).toString();
            } else {
                return MoreObjects.toStringHelper(this)
                                  .addValue(getNow(null)).toString();
            }
        }

        final int count = getNumberOfDependents();
        if (count == 0) {
            return MoreObjects.toStringHelper(this)
                              .addValue("not completed").toString();
        } else {
            return MoreObjects.toStringHelper(this)
                              .addValue("not completed")
                              .add("dependents", count).toString();
        }
    }
}
