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

import static com.linecorp.armeria.internal.common.DefaultCancellationScheduler.noopCancellationTask;

import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

public interface CancellationScheduler {

    static CancellationScheduler of(long timeoutNanos) {
        return new DefaultCancellationScheduler(timeoutNanos);
    }

    static CancellationScheduler finished(boolean server) {
        final CancellationScheduler cancellationScheduler = CancellationScheduler.of(0);
        cancellationScheduler
                .init(ImmediateEventExecutor.INSTANCE, noopCancellationTask, 0, server);
        cancellationScheduler.finishNow();
        return cancellationScheduler;
    }

    static CancellationScheduler noop() {
        return NoopCancellationScheduler.INSTANCE;
    }

    CancellationTask noopCancellationTask = new CancellationTask() {
        @Override
        public boolean canSchedule() {
            return true;
        }

        @Override
        public void run(Throwable cause) { /* no-op */ }
    };

    void init(EventExecutor eventLoop, CancellationTask task, long timeoutNanos, boolean server);

    void clearTimeout();

    void clearTimeout(boolean resetTimeout);

    void setTimeoutNanos(TimeoutMode mode, long timeoutNanos);

    void finishNow();

    void finishNow(@Nullable Throwable cause);

    boolean isFinished();

    @Nullable Throwable cause();

    long timeoutNanos();

    long startTimeNanos();

    CompletableFuture<Throwable> whenCancelling();

    CompletableFuture<Throwable> whenCancelled();

    @Deprecated
    CompletableFuture<Void> whenTimingOut();

    @Deprecated
    CompletableFuture<Void> whenTimedOut();

    @VisibleForTesting
    boolean isInitialized();

    enum State {
        INIT,
        INACTIVE,
        SCHEDULED,
        FINISHING,
        FINISHED
    }

    /**
     * A cancellation task invoked by the scheduler when its timeout exceeds or invoke by the user.
     */
    interface CancellationTask {
        /**
         * Returns {@code true} if the cancellation task can be scheduled.
         */
        boolean canSchedule();

        /**
         * Invoked by the scheduler with the cause of cancellation.
         */
        void run(Throwable cause);
    }
}
