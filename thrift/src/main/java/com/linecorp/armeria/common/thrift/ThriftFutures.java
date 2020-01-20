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
package com.linecorp.armeria.common.thrift;

/**
 * Static factory methods pertaining to the {@link ThriftCompletableFuture} and {@link ThriftListenableFuture}.
 *
 * @deprecated Use the static factory methods in {@link ThriftFuture} or {@link ThriftListenableFuture}.
 */
@Deprecated
public final class ThriftFutures {
    /**
     * Returns a new {@link ThriftCompletableFuture} instance that has its value set immediately.
     *
     * @deprecated Use {@link ThriftFuture#completedFuture(Object)}.
     */
    @Deprecated
    public static <T> ThriftCompletableFuture<T> successfulCompletedFuture(T value) {
        final ThriftCompletableFuture<T> future = new ThriftCompletableFuture<>();
        future.onComplete(value);
        return future;
    }

    /**
     * Returns a new {@link ThriftCompletableFuture} instance that has an exception set immediately.
     *
     * @deprecated Use {@link ThriftFuture#exceptionallyCompletedFuture(Throwable)}.
     */
    @Deprecated
    public static <T> ThriftCompletableFuture<T> failedCompletedFuture(Exception e) {
        final ThriftCompletableFuture<T> future = new ThriftCompletableFuture<>();
        future.onError(e);
        return future;
    }

    /**
     * Returns a new {@link ThriftListenableFuture} instance that has its value set immediately.
     *
     * @deprecated Use {@link ThriftListenableFuture#completedFuture(Object)}.
     */
    @Deprecated
    public static <T> ThriftListenableFuture<T> successfulListenableFuture(T value) {
        final ThriftListenableFuture<T> future = new ThriftListenableFuture<>();
        future.onComplete(value);
        return future;
    }

    /**
     * Returns a new {@link ThriftListenableFuture} instance that has an exception set immediately.
     *
     * @deprecated Use {@link ThriftListenableFuture#exceptionallyCompletedFuture(Throwable)}.
     */
    @Deprecated
    public static <T> ThriftListenableFuture<T> failedListenableFuture(Exception e) {
        final ThriftListenableFuture<T> future = new ThriftListenableFuture<>();
        future.onError(e);
        return future;
    }

    private ThriftFutures() {}
}
