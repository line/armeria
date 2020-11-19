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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

/**
 * A {@link CompletableFuture} which prevents the caller from completing it. An attempt to call any of
 * the following methods will trigger an {@link UnsupportedOperationException}:
 * <ul>
 *   <li>{@link #complete(Object)}</li>
 *   <li>{@link #completeExceptionally(Throwable)}</li>
 *   <li>{@link #obtrudeValue(Object)}</li>
 *   <li>{@link #obtrudeException(Throwable)}</li>
 * </ul>
 * Also, {@link #cancel(boolean)} will do nothing but returning whether cancelled or not.
 */
public class UnmodifiableFuture<T> extends EventLoopCheckingFuture<T> {

    private static final UnmodifiableFuture<?> NIL;
    private static final UnmodifiableFuture<Boolean> TRUE;
    private static final UnmodifiableFuture<Boolean> FALSE;

    static {
        NIL = new UnmodifiableFuture<>();
        NIL.doComplete(null);
        TRUE = new UnmodifiableFuture<>();
        TRUE.doComplete(Boolean.TRUE);
        FALSE = new UnmodifiableFuture<>();
        FALSE.doComplete(Boolean.FALSE);
    }

    /**
     * Returns an {@link UnmodifiableFuture} which has been completed with the specified {@code value}.
     */
    public static <U> UnmodifiableFuture<U> completedFuture(@Nullable U value) {
        if (value == null) {
            @SuppressWarnings("unchecked")
            final UnmodifiableFuture<U> cast = (UnmodifiableFuture<U>) NIL;
            return cast;
        }

        if (value == Boolean.TRUE) {
            @SuppressWarnings("unchecked")
            final UnmodifiableFuture<U> cast = (UnmodifiableFuture<U>) TRUE;
            return cast;
        }

        if (value == Boolean.FALSE) {
            @SuppressWarnings("unchecked")
            final UnmodifiableFuture<U> cast = (UnmodifiableFuture<U>) FALSE;
            return cast;
        }

        final UnmodifiableFuture<U> future = new UnmodifiableFuture<>();
        future.doComplete(value);
        return future;
    }

    /**
     * Returns an {@link UnmodifiableFuture} which has been completed exceptionally with the specified
     * {@link Throwable}.
     */
    public static <U> UnmodifiableFuture<U> exceptionallyCompletedFuture(Throwable cause) {
        requireNonNull(cause, "cause");
        final UnmodifiableFuture<U> future = new UnmodifiableFuture<>();
        future.doCompleteExceptionally(cause);
        return future;
    }

    /**
     * Returns an {@link UnmodifiableFuture} which will be completed when the specified
     * {@link CompletableFuture} is completed.
     */
    public static <U> UnmodifiableFuture<U> wrap(CompletableFuture<U> future) {
        requireNonNull(future, "future");
        final UnmodifiableFuture<U> unmodifiable = new UnmodifiableFuture<>();
        future.handle((result, cause) -> {
            if (cause != null) {
                unmodifiable.doCompleteExceptionally(Exceptions.peel(cause));
            } else {
                unmodifiable.doComplete(result);
            }
            return null;
        });
        return unmodifiable;
    }

    /**
     * Creates a new {@link UnmodifiableFuture}.
     */
    protected UnmodifiableFuture() {}

    /**
     * Throws an {@link UnsupportedOperationException}.
     */
    @Override
    public boolean complete(@Nullable T value) {
        throw new UnsupportedOperationException();
    }

    /**
     * Completes with a non-exceptional {@code value}, unless already completed.
     */
    protected void doComplete(@Nullable T value) {
        super.complete(value);
    }

    /**
     * Throws an {@link UnsupportedOperationException}.
     */
    @Override
    public boolean completeExceptionally(Throwable ex) {
        throw new UnsupportedOperationException();
    }

    /**
     * Completes with the specified {@link Throwable}, unless already completed.
     */
    protected void doCompleteExceptionally(Throwable cause) {
        super.completeExceptionally(cause);
    }

    /**
     * Does nothing but returning whether this future has been cancelled or not.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return isCancelled();
    }

    /**
     * Completes this {@link CompletableFuture} with a {@link CancellationException}, unless already completed.
     */
    protected boolean doCancel() {
        return super.cancel(false);
    }

    /**
     * Throws an {@link UnsupportedOperationException}.
     */
    @Override
    public void obtrudeValue(@Nullable T value) {
        throw new UnsupportedOperationException();
    }

    /**
     * Throws an {@link UnsupportedOperationException}.
     */
    @Override
    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException();
    }
}
