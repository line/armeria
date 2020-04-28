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

import javax.annotation.Nullable;

/**
 * A {@link RetryRuleDecision} that determines whether a {@link RetryRule} retries with a {@link Backoff}, stop,
 * or skip the current {@link RetryRule}.
 */
public final class RetryRuleDecision {

    private static final RetryRuleDecision STOP = new RetryRuleDecision(null);
    private static final RetryRuleDecision NEXT = new RetryRuleDecision(null);

    /**
     * Returns a {@link RetryRuleDecision} that retries with the specified {@link Backoff}.
     */
    public static RetryRuleDecision retry(Backoff backoff) {
        return new RetryRuleDecision(backoff);
    }

    /**
     * Returns a {@link RetryRuleDecision} that never retries.
     */
    public static RetryRuleDecision stop() {
        return STOP;
    }

    /**
     * Returns a {@link RetryRuleDecision} that skips the current {@link RetryRule} and
     * tries to retry with the next {@link RetryRule}.
     */
    public static RetryRuleDecision next() {
        return NEXT;
    }

    @Nullable
    private final Backoff backoff;

    private RetryRuleDecision(@Nullable Backoff backoff) {
        this.backoff = backoff;
    }

    @Nullable
    Backoff backoff() {
        return backoff;
    }

    @Override
    public String toString() {
        if (this == STOP) {
            return "RetryRuleDecision(STOP)";
        } else if (this == NEXT) {
            return "RetryRuleDecision(NEXT)";
        } else {
            return "RetryRuleDecision(RETRY(" + backoff + "))";
        }
    }
}
