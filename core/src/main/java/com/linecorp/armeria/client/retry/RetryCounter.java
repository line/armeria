/*
 * Copyright 2025 LINE Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.client.retry;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A counter that keeps track of the number of attempts made so far for a {@link RetryingClient}.
 * In particular, it keeps track of the total number of attempts but also
 * the number of attempts made with each {@link Backoff}.
 *
 * <p>
 *     Implementors do not need to be thread-safe.
 * </p>
 */
interface RetryCounter {
    /**
     * Records an attempt in that it increases the total number of attempts by one. If {@code backoff} is not
     * {@code null}, the number of attempts for that {@code backoff} is increased as well.
     *
     * @param backoff the backoff used for the attempt, or {@code null} if no backoff was used.
     */
    void consumeAttemptFrom(@Nullable Backoff backoff);

    /**
     * Returns the number of attempts executed so far with {@code backoff}.
     *
     * @param backoff the backoff whose number of attempts is requested
     * @return the number of attempts executed so far with {@code backoff}
     */
    int attemptsSoFarWithBackoff(Backoff backoff);

    /**
     * Returns {@code true} if the total number of attempts has reached the maximum number of attempts
     * configured in the {@link RetryConfig}.
     *
     * @return {@code true} if the total number of attempts has reached the maximum number of attempts
     *         configured in the {@link RetryConfig}
     */
    boolean hasReachedMaxAttempts();
}
