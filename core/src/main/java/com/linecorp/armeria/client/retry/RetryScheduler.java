/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.client.retry;

import java.util.concurrent.CompletableFuture;

/**
 * A scheduler that runs retry tasks sequentially on a designated event loop,
 * while enforcing a fixed request deadline and minimum backoff between attempts.
 *
 * <p>
 *  NOTE: All methods of {@link RetryScheduler} must be invoked from the
 *  {@code retryEventLoop}. Implementations of {@link RetryScheduler} therefore do not need to be thread-safe.
 * </p>
 */
interface RetryScheduler {
    /**
     * Tries to schedule the given {@code retryTask} to be run after the given {@code delayMillis}
     * or as early as the minimum backoff delays applied for the next schedule allow.
     * Implementations are advised to execute the {@code retryTask} directly if
     * {@code delayMillis} is {@code 0}.
     *
     * <p>
     *  The method returns {@code true} if the {@code retryTask} was successfully scheduled or
     *  {@code false} if not. Note that a return value of {@code false} does not necessarily indicate that
     *  something exceptional happened during scheduling. For example, {@code delayMillis} might be too long
     *  for the deadline in which case this method returns {@code false}
     *  but those events would not {@link #close()} the scheduler exceptionally.
     * </p>
     *
     * @param retryTask the task to run to perform a retry
     * @param delayMillis the delay in milliseconds after which the {@code retryTask} should be run
     *
     * @return {@code true} if the {@code retryTask} was successfully scheduled or {@code false} if not.
     */
    boolean trySchedule(Runnable retryTask, long delayMillis);

    /**
     * Applies the given {@code minimumBackoffMillis} for the next retry scheduling.
     * This means that the next retry task will not be scheduled before the given
     * {@code minimumBackoffMillis} has elapsed since the call to this method.
     *
     * <p>
     *     This method must not be called while executing another retry task.
     * </p>
     *
     * @param minimumBackoffMillis the minimum backoff in milliseconds to apply for the next retry scheduling
     */
    void applyMinimumBackoffMillisForNextRetry(long minimumBackoffMillis);

    /**
     * Closes the scheduler which is cancelling the next retry task if any and
     * completing the future from {@link #whenClosed()}.
     */
    void close();

    /**
     * Returns a future that is completed when the scheduler is closed. It completes
     * <ul>
     *     <li>normally, when the scheduler is closed after a successful call to {@link #close()}, or</li>
     *     <li>exceptionally, when it is not possible to run a retry task once it was meant to be scheduled.
     *     </li>
     * </ul>
     *
     * <p>
     *  The future is guaranteed to be completed on the {@code retryEventLoop}.
     * </p>
     *
     * @return a future that is completed when the scheduler is closed
     */
    CompletableFuture<Void> whenClosed();
}
