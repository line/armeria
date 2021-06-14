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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.Exceptions;

/**
 * An RPC {@link Response}. It is a {@link CompletionStage} whose result signifies the return value of an RPC
 * call.
 */
public interface RpcResponse extends Response, Future<Object>, CompletionStage<Object> {

    /**
     * Returns a newly created successfully complete {@link RpcResponse}.
     */
    static RpcResponse of(@Nullable Object value) {
        final CompletableRpcResponse future = new CompletableRpcResponse();
        future.complete(value);
        return future;
    }

    /**
     * Returns a newly created exceptionally complete {@link RpcResponse}.
     */
    static RpcResponse ofFailure(Throwable cause) {
        requireNonNull(cause, "cause");
        final CompletableRpcResponse future = new CompletableRpcResponse();
        future.completeExceptionally(cause);
        return future;
    }

    /**
     * Creates a new {@link RpcResponse} that is completed successfully or exceptionally based on the
     * completion of the specified {@link CompletionStage}.
     */
    static RpcResponse from(CompletionStage<?> stage) {
        requireNonNull(stage, "stage");
        final CompletableRpcResponse res = new CompletableRpcResponse();
        stage.handle((value, cause) -> {
            if (cause != null) {
                res.completeExceptionally(Exceptions.peel(cause));
            } else if (value instanceof RpcResponse) {
                ((RpcResponse) value).handle((rpcResponseResult, rpcResponseCause) -> {
                    if (rpcResponseCause != null) {
                        res.completeExceptionally(Exceptions.peel(rpcResponseCause));
                    } else {
                        res.complete(rpcResponseResult);
                    }
                    return null;
                });
            } else {
                res.complete(value);
            }
            return null;
        });
        return res;
    }

    /**
     * Returns the result value if completed successfully or
     * throws an unchecked exception if completed exceptionally.
     *
     * @see CompletableFuture#join()
     */
    Object join();

    /**
     * Returns the specified {@code valueIfAbsent} when not complete, or
     * returns the result value or throws an exception when complete.
     *
     * @see CompletableFuture#getNow(Object)
     */
    Object getNow(Object valueIfAbsent);

    /**
     * Returns the cause of the failure if this {@link RpcResponse} completed exceptionally.
     *
     * @return the cause, or
     *         {@code null} if this {@link RpcResponse} completed successfully or did not complete yet.
     */
    @Nullable
    Throwable cause();

    /**
     * Returns {@code true} if this {@link RpcResponse} completed exceptionally.
     *
     * @see CompletableFuture#isCompletedExceptionally()
     */
    boolean isCompletedExceptionally();

    /**
     * Returns a {@link CompletableFuture} which completes when this {@link RpcResponse} completes.
     */
    @Override
    default CompletableFuture<?> whenComplete() {
        return toCompletableFuture();
    }
}
