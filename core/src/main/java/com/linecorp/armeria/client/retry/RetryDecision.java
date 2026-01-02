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

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

/**
 * A {@link RetryDecision} that determines whether a {@link RetryRule} retries with a {@link Backoff},
 * skips the current {@link RetryRule} or no retries.
 */
public final class RetryDecision {

    private static final RetryDecision NO_RETRY = new RetryDecision(null, -1);
    private static final RetryDecision NEXT = new RetryDecision(null, 0);
    static final RetryDecision DEFAULT = new RetryDecision(Backoff.ofDefault(), 1);

    /**
     * Returns a {@link RetryDecision} that retries with the specified {@link Backoff}.
     * The permits will be {@code 1} by default.
     */
    public static RetryDecision retry(Backoff backoff) {
        return retry(backoff, 1);
    }

    /**
     * Returns a {@link RetryDecision} that retries with the specified {@link Backoff}.
     */
    @SuppressWarnings("FloatingPointEquality")
    public static RetryDecision retry(Backoff backoff, double permits) {
        if (backoff == DEFAULT.backoff() && permits == DEFAULT.permits()) {
            return DEFAULT;
        }
        return new RetryDecision(requireNonNull(backoff, "backoff"), permits);
    }

    /**
     * Returns a {@link RetryDecision} that never retries.
     * The permits will be {@code -1} by default.
     */
    public static RetryDecision noRetry() {
        return NO_RETRY;
    }

    /**
     * Returns a {@link RetryDecision} that never retries.
     */
    public static RetryDecision noRetry(double permits) {
        return new RetryDecision(null, permits);
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
    private final double permits;

    private RetryDecision(@Nullable Backoff backoff, double permits) {
        this.backoff = backoff;
        this.permits = permits;
    }

    @Nullable
    Backoff backoff() {
        return backoff;
    }

    /**
     * The number of permits associated with this {@link RetryDecision}.
     * This may be used by {@link RetryLimiter} to determine whether retry requests should
     * be limited or not. The semantics of whether or how the returned value affects {@link RetryLimiter}
     * depends on what type of {@link RetryLimiter} is used.
     */
    public double permits() {
        return permits;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = MoreObjects.toStringHelper(this);
        if (this == NEXT) {
            stringHelper.add("type", "NEXT");
        } else if (backoff != null) {
            stringHelper.add("type", "RETRY");
        } else {
            stringHelper.add("type", "NO_RETRY");
        }
        return stringHelper.omitNullValues()
                           .add("backoff", backoff)
                           .add("permits", permits)
                           .toString();
    }
}
