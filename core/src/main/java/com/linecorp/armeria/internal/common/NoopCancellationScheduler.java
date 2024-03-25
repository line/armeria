/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

final class NoopCancellationScheduler implements CancellationScheduler {

    static final CancellationScheduler INSTANCE = new NoopCancellationScheduler();

    private static final CompletableFuture<Throwable> THROWABLE_FUTURE =
            UnmodifiableFuture.wrap(new CompletableFuture<>());
    private static final CompletableFuture<Void> VOID_FUTURE =
            UnmodifiableFuture.wrap(new CompletableFuture<>());

    private NoopCancellationScheduler() {
    }

    @Override
    public void initAndStart(EventExecutor eventLoop, CancellationTask task) {
    }

    @Override
    public void init(EventExecutor eventLoop) {
    }

    @Override
    public void start(CancellationTask task) {
    }

    @Override
    public void clearTimeout() {
    }

    @Override
    public void clearTimeout(boolean resetTimeout) {
    }

    @Override
    public void setTimeoutNanos(TimeoutMode mode, long timeoutNanos) {
    }

    @Override
    public void finishNow() {
    }

    @Override
    public void finishNow(@Nullable Throwable cause) {
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    @Nullable
    public Throwable cause() {
        return null;
    }

    @Override
    public long timeoutNanos() {
        return 0;
    }

    @Override
    public long startTimeNanos() {
        return 0;
    }

    @Override
    public CompletableFuture<Throwable> whenCancelling() {
        return THROWABLE_FUTURE;
    }

    @Override
    public CompletableFuture<Throwable> whenCancelled() {
        return THROWABLE_FUTURE;
    }

    @Override
    public CompletableFuture<Void> whenTimingOut() {
        return VOID_FUTURE;
    }

    @Override
    public CompletableFuture<Void> whenTimedOut() {
        return VOID_FUTURE;
    }
}
