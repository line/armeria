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
package com.linecorp.armeria.common.thrift;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

/**
 * A {@link CompletableFuture} that can be passed in as an {@link AsyncMethodCallback}
 * when making an asynchronous client-side Thrift RPC.
 */
public final class ThriftFuture<T> extends EventLoopCheckingFuture<T> implements AsyncMethodCallback<T> {

    /**
     * Returns a new {@link ThriftFuture} instance that has its value set immediately.
     */
    public static <T> ThriftFuture<T> completedFuture(@Nullable T value) {
        final ThriftFuture<T> future = new ThriftFuture<>();
        future.onComplete(value);
        return future;
    }

    /**
     * Returns a new {@link ThriftFuture} instance that has an exception set immediately.
     */
    public static <T> ThriftFuture<T> exceptionallyCompletedFuture(Throwable cause) {
        requireNonNull(cause, "cause");
        final ThriftFuture<T> future = new ThriftFuture<>();
        future.onError(cause instanceof Exception ? (Exception) cause : new CompletionException(cause));
        return future;
    }

    @Override
    public void onComplete(@Nullable T value) {
        complete(value);
    }

    @Override
    public void onError(Exception cause) {
        completeExceptionally(cause);
    }
}
