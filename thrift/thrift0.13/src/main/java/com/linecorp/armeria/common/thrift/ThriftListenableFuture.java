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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionException;

import org.apache.thrift.async.AsyncMethodCallback;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link ListenableFuture} that can be passed in as an {@link AsyncMethodCallback}
 * when making an asynchronous client-side Thrift RPC.
 */
public final class ThriftListenableFuture<T> extends AbstractFuture<T> implements AsyncMethodCallback<T> {

    /**
     * Returns a new {@link ThriftListenableFuture} instance that has its value set immediately.
     */
    public static <T> ThriftListenableFuture<T> completedFuture(@Nullable T value) {
        final ThriftListenableFuture<T> future = new ThriftListenableFuture<>();
        future.onComplete(value);
        return future;
    }

    /**
     * Returns a new {@link ThriftListenableFuture} instance that has an exception set immediately.
     */
    public static <T> ThriftListenableFuture<T> exceptionallyCompletedFuture(Throwable cause) {
        requireNonNull(cause, "cause");
        final ThriftListenableFuture<T> future = new ThriftListenableFuture<>();
        future.onError(cause instanceof Exception ? (Exception) cause : new CompletionException(cause));
        return future;
    }

    @Override
    public void onComplete(@Nullable T value) {
        set(value);
    }

    @Override
    public void onError(Exception cause) {
        setException(cause);
    }
}
