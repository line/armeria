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
 *  Sequential means that {@link #trySchedule(Runnable, long)} can only be called with not other retry task
 *  scheduled (i.e. with {@link #hasNextRetryTask()} returning {@code false}).
 * </p>
 *
 * <p>
 *  NOTE: All methods of {@link RetryScheduler} must be invoked from the
 *  {@code retryEventLoop}. Implementations of {@link RetryScheduler} therefore do not need to be thread-safe.
 * </p>
 */
interface RetryScheduler {
    /**
     * Tries to schedule the given {@code retryTask} to be run after the given {@code delayMillis}
     * or as early as the minimum backoff delays applied for the next schedule allow. Scheduling might
     * not succeed which can be checked by calling {@link #hasNextRetryTask()}.
     *
     * @param retryTask the task to run to perform a retry
     * @param delayMillis the delay in milliseconds after which the {@code retryTask} should be run
     */
    void trySchedule(Runnable retryTask, long delayMillis);

    /**
     * Returns {@code true} if there is a retry task scheduled. If this method returns {@code true},
     * the scheduler guarantees that it is going to run a retry task at some point in the future
     * on the retryEventLoop. If it is not possible to run a retry task, the scheduler guarantees
     * to complete the future from {@link #whenClosed()} exceptionally.
     *
     * @return {@code true} if there is a retry task scheduled
     */
    boolean hasNextRetryTask();

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

    /**
     * Applies the given {@code minimumBackoffMillis} for the next retry scheduling.
     * This means that the next retry task will not be scheduled before the given
     * {@code minimumBackoffMillis} has elapsed since the call to this method.
     *
     * <p>
     *     This method must not be called when {@link #hasNextRetryTask()} returns {@code true} as the scheduler
     *     only supports sequential retrying at the moment.
     * </p>
     *
     * @param minimumBackoffMillis the minimum backoff in milliseconds to apply for the next retry scheduling
     */
    void applyMinimumBackoffMillisForNextRetry(long minimumBackoffMillis);
}
