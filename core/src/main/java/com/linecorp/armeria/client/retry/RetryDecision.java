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

package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RetryDecision} that determines whether a {@link RetryRule} retries with a {@link Backoff},
 * skips the current {@link RetryRule} or no retries.
 */
public final class RetryDecision {

    private static final RetryDecision NO_RETRY = new RetryDecision(null);
    private static final RetryDecision NEXT = new RetryDecision(null);
    static final RetryDecision DEFAULT = new RetryDecision(Backoff.ofDefault());

    /**
     * Returns a {@link RetryDecision} that retries with the specified {@link Backoff}.
     */
    public static RetryDecision retry(Backoff backoff) {
        if (backoff == Backoff.ofDefault()) {
            return DEFAULT;
        }
        return new RetryDecision(requireNonNull(backoff, "backoff"));
    }

    /**
     * Returns a {@link RetryDecision} that never retries.
     */
    public static RetryDecision noRetry() {
        return NO_RETRY;
    }

    /**
     * Returns a {@link RetryDecision} that skips the current {@link RetryRule} and
     * tries to retry with the next {@link RetryRule}.
     */
    public static RetryDecision next() {
        return NEXT;
    }

    @Nullable
    private final Backoff backoff;

    private RetryDecision(@Nullable Backoff backoff) {
        this.backoff = backoff;
    }

    @Nullable
    Backoff backoff() {
        return backoff;
    }

    @Override
    public String toString() {
        if (this == NO_RETRY) {
            return "RetryDecision(NO_RETRY)";
        } else if (this == NEXT) {
            return "RetryDecision(NEXT)";
        } else {
            return "RetryDecision(RETRY(" + backoff + "))";
        }
    }
}
