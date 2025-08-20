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

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class RetryCounter {
    private final int maxAttempts;

    private int numberAttemptsSoFar;
    @Nullable
    private Backoff lastBackoff;
    private int numberAttemptsForLastBackoff;

    RetryCounter(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts: " + maxAttempts + " (expected: > 0)");
        }
        this.maxAttempts = maxAttempts;
        numberAttemptsSoFar = 0;
        lastBackoff = null;
        numberAttemptsForLastBackoff = 0;
    }

    void recordAttemptWith(@Nullable Backoff backoff) {
        if (hasReachedMaxAttempts()) {
            throw new IllegalStateException("Exceeded the maximum number of attempts: " + maxAttempts);
        }

        ++numberAttemptsSoFar;

        if (backoff != null) {
            if (lastBackoff != backoff) {
                lastBackoff = backoff;
                numberAttemptsForLastBackoff = 0;
            }
            numberAttemptsForLastBackoff++;
        }
    }

    int attemptNumber() {
        return numberAttemptsSoFar;
    }

    int attemptNumberForBackoff(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        if (lastBackoff != backoff) {
            return 0;
        } else {
            return numberAttemptsForLastBackoff;
        }
    }

    boolean hasReachedMaxAttempts() {
        return numberAttemptsSoFar >= maxAttempts;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("maxAttempts", maxAttempts)
                .add("numberAttemptsSoFar", numberAttemptsSoFar)
                .add("lastBackoff", lastBackoff)
                .add("numberAttemptsWithThisBackoffSoFar", numberAttemptsForLastBackoff)
                .toString();
    }
}
