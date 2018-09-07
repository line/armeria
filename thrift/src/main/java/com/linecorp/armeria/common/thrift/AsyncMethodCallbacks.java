/*
 * Copyright 2018 LINE Corporation
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
import java.util.concurrent.CompletionStage;

import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A utility class that bridges the gap between {@link CompletionStage} and {@link AsyncMethodCallback}.
 */
public final class AsyncMethodCallbacks {

    /**
     * Adds a callback that transfers the outcome of the specified {@link CompletionStage} to the specified
     * {@link AsyncMethodCallback}.
     *
     * <pre>{@code
     * > public class MyThriftService implements ThriftService.AsyncIface {
     * >     @Override
     * >     public void myServiceMethod(AsyncMethodCallback<MyResult> callback) {
     * >         final CompletableFuture<MyResult> future = ...;
     * >         AsyncMethodCallbacks.transfer(future, callback);
     * >     }
     * > }
     * }</pre>
     */
    public static <T> void transfer(CompletionStage<T> src, AsyncMethodCallback<? super T> dest) {
        requireNonNull(src, "src");
        requireNonNull(dest, "dest");
        src.whenComplete((res, cause) -> {
            try {
                if (cause != null) {
                    invokeOnError(dest, cause);
                } else {
                    dest.onComplete(res);
                }
            } catch (Exception e) {
                CompletionActions.log(e);
            }
        });
    }

    /**
     * Invokes {@link AsyncMethodCallback#onError(Exception)}. If the specified {@code cause} is not an
     * {@link Exception}, it will be wrapped with a {@link CompletionException}.
     */
    public static void invokeOnError(AsyncMethodCallback<?> callback, Throwable cause) {
        requireNonNull(callback, "callback");
        requireNonNull(cause, "cause");
        if (cause instanceof Exception) {
            callback.onError((Exception) cause);
        } else {
            callback.onError(new CompletionException(cause.toString(), cause));
        }
    }

    private AsyncMethodCallbacks() {}
}
