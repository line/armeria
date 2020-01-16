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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

/**
 * A {@link CompletableFuture} which prevents the caller from completing it.
 */
final class UnupdatableCompletableFuture<T> extends CompletableFuture<T> {

    public static <U> UnupdatableCompletableFuture<U> completedFuture(@Nullable U value) {
        final UnupdatableCompletableFuture<U> future = new UnupdatableCompletableFuture<>();
        future.doComplete(value);
        return future;
    }

    public static <U> UnupdatableCompletableFuture<U> exceptionallyCompletedFuture(Throwable cause) {
        requireNonNull(cause, "cause");
        final UnupdatableCompletableFuture<U> future = new UnupdatableCompletableFuture<>();
        future.doCompleteExceptionally(cause);
        return future;
    }

    public static <U> UnupdatableCompletableFuture<U> wrap(CompletableFuture<U> future) {
        final UnupdatableCompletableFuture<U> unupdatable = new UnupdatableCompletableFuture<>();
        future.handle((result, cause) -> {
            if (cause != null) {
                unupdatable.doCompleteExceptionally(Exceptions.peel(cause));
            } else {
                unupdatable.doComplete(result);
            }
            return null;
        });
        return unupdatable;
    }

    private UnupdatableCompletableFuture() {}

    @Override
    public boolean complete(@Nullable T value) {
        throw new UnsupportedOperationException();
    }

    private void doComplete(@Nullable T value) {
        super.complete(value);
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        throw new UnsupportedOperationException();
    }

    private void doCompleteExceptionally(Throwable cause) {
        super.completeExceptionally(cause);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public void obtrudeValue(@Nullable T value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException();
    }
}
