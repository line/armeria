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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * A retried request that manages multiple retry attempts.
 *
 * <p>
 *  NOTE: All methods of {@link RetryScheduler} must be invoked from the
 *  {@code retryEventLoop}. Implementations of {@link RetriedRequest} therefore do not need to be thread-safe.
 * </p>
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
interface RetriedRequest<I extends Request, O extends Response> {
    /**
     * The result of an attempt execution.
     */
    final class AttemptExecutionResult {
        private final int attemptNumber;
        private final RetryDecision decision;
        private final long minimumBackoffMillis;

        AttemptExecutionResult(int attemptNumber, RetryDecision decision,
                               long minimumBackoffMillis) {
            this.attemptNumber = attemptNumber;
            this.decision = decision;
            this.minimumBackoffMillis = minimumBackoffMillis;
        }

        /**
         * Returns the attempt number. It can be used to {@link RetriedRequest#abort(int, Throwable)} or
         * {@link RetriedRequest#commit(int)} the attempt.
         *
         * @return the attempt number.
         */
        public int attemptNumber() {
            return attemptNumber;
        }

        /**
         * Returns the decision made by {@link RetryRule} from the {@link RetryConfig} on the attempt response.
         * The attempt response was prepared enough for the {@link RetryRule} to make a decision. This could
         * include awaiting receiving headers, content, or trailers for example.
         *
         * @return the decision made by the {@link RetryRule}
         */
        public RetryDecision decision() {
            return decision;
        }

        /**
         * Returns the minimum backoff in milliseconds for the next retry attempt
         * returned by the remote peer that received the attempt.
         * If negative, the minimum backoff is either not available or not requested.
         *
         * @return the minimum backoff in milliseconds. If negative, no minimum backoff should be applied for
         *         the next retry attempt.
         */
        public long minimumBackoffMillis() {
            return minimumBackoffMillis;
        }
    }

    /**
     * Tries to execute a new attempt on {@code delegate} based on the {@link RetryConfig}.
     *
     * <p>
     * It completes with a {@link AttemptExecutionResult} if the attempt was successfully executed and decided
     * by the {@link RetryRule} from the {@link RetryConfig}.
     * </p>
     *
     * <p>
     * It completed exceptionally in case something unexpected happened during the attempt execution.
     * In particular, it completes exceptionally with a {@link AbortedAttemptException} if the attempt
     * was aborted by the {@link RetriedRequest}. This could happen if the {@link RetriedRequest} completes
     * concurrently while the attempt is being executed.
     * </p>
     *
     * @param delegate the next {@link Client} in the decoration chain
     * @return a future completed with the result of the attempt execution. It completes with a
     *         {@link AbortedAttemptException} in case the attempt was aborted in a controlled way.
     */
    CompletionStage<AttemptExecutionResult> executeAttempt(Client<I, O> delegate);

    /**
     * Commits the attempt with the specified {@code attemptNumber}. Committing an attempt includes:
     * <ul>
     *     <li>completing the original request with the attempt's response,</li>
     *     <li>aborting all other pending attempts, and</li>
     *     <li>marking the {@link RetriedRequest} as completed.</li>
     * </ul>
     * Does nothing if the {@link RetriedRequest} is already completed.
     *
     * @param attemptNumber the attempt number to commit
     */
    void commit(int attemptNumber);

    /**
     * Aborts the attempt identified by the given {@code attemptNumber}.
     *
     * <p>The attempt must have been previously started via
     * {@link #executeAttempt(Client)}. Aborting an attempt ensures that its
     * response and {@link RequestLog}
     * complete, but that it will not be committed as the final result of the
     * {@link RetriedRequest}.</p>
     *
     * <p>If the {@link RetriedRequest} is already completed, this method has no effect.
     * If the specified attempt has not been started, an {@link IllegalStateException} is thrown.</p>
     *
     * @param attemptNumber the identifier of the attempt to abort
     * @throws IllegalStateException if the attempt has not been executed
     */
    void abort(int attemptNumber, Throwable cause);

    /**
     * Aborts all attempts and completes the {@link RetriedRequest} exceptionally with the specified
     * {@code cause}. Marks the {@link RetriedRequest} as completed. Does nothing if the {@link RetriedRequest}
     * was already completed before.
     */
    void abort(Throwable cause);

    /**
     * Returns the original {@link Response} that is completed when the {@link RetriedRequest} is completed.
     * @return the {@link Response} with either a committed attempt's response or with an exception from a call
     *         to {@link #abort(Throwable)} or from an unexpected error during the processing of an attempt.
     */
    O res();
}
