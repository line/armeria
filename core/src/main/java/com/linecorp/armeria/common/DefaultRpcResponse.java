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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Exceptions;

/**
 * Default {@link RpcResponse} implementation.
 */
public class DefaultRpcResponse extends CompletableFuture<Object> implements RpcResponse {

    private static final CancellationException CANCELLED = Exceptions.clearTrace(new CancellationException());

    private static final AtomicReferenceFieldUpdater<DefaultRpcResponse, Throwable> causeUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultRpcResponse.class, Throwable.class, "cause");

    private volatile Throwable cause;

    /**
     * Creates a new incomplete response.
     */
    public DefaultRpcResponse() {}

    /**
     * Creates a new successfully complete response.
     *
     * @param result the result or an RPC call
     */
    public DefaultRpcResponse(@Nullable Object result) {
        complete(result);
    }

    /**
     * Creates a new exceptionally complete response.
     *
     * @param cause the cause of failure
     */
    public DefaultRpcResponse(Throwable cause) {
        requireNonNull(cause, "cause");
        completeExceptionally(cause);
    }

    @Override
    public final Throwable cause() {
        return cause;
    }

    @Override
    public boolean completeExceptionally(Throwable cause) {
        causeUpdater.compareAndSet(this, null, requireNonNull(cause));
        return super.completeExceptionally(cause);
    }

    @Override
    public void obtrudeException(Throwable cause) {
        this.cause = requireNonNull(cause);
        super.obtrudeException(cause);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return completeExceptionally(CANCELLED) || isCancelled();
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
