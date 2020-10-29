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

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Duration;

import com.linecorp.armeria.common.Flags;

/**
 * Builds a {@link RetryConfig}.
 */
public final class RetryConfigBuilder {
    int maxTotalAttempts = Flags.defaultMaxTotalAttempts();
    long responseTimeoutMillisForEachAttempt = Flags.defaultResponseTimeoutMillis();

    /**
     * Sets maxTotalAttempts.
     */
    public RetryConfigBuilder maxTotalAttempts(int maxTotalAttempts) {
        checkArgument(
                maxTotalAttempts > 0,
                "maxTotalAttempts: %s (expected: > 0)",
                maxTotalAttempts);
        this.maxTotalAttempts = maxTotalAttempts;
        return this;
    }

    /**
     * Sets responseTimeoutMillisForEachAttempt.
     */
    public RetryConfigBuilder responseTimeoutMillisForEachAttempt(long responseTimeoutMillisForEachAttempt) {
        checkArgument(
                responseTimeoutMillisForEachAttempt >= 0,
                "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        return this;
    }

    /**
     * Sets responseTimeoutMillisForEachAttempt by converting responseTimeoutForEachAttempt to millis.
     */
    public RetryConfigBuilder responseTimeoutForEachAttempt(Duration responseTimeoutMillisForEachAttempt) {
        final long millis = responseTimeoutMillisForEachAttempt.toMillis();
        checkArgument(
                millis >= 0,
                "responseTimeoutForEachAttempt.toMillis(): %s (expected: >= 0)",
                millis);
        this.responseTimeoutMillisForEachAttempt = millis;
        return this;
    }

    /**
     * Builds a {@link RetryConfig} from this builder's values and returns it.
     */
    public RetryConfig build() {
        return new RetryConfig(maxTotalAttempts, responseTimeoutMillisForEachAttempt);
    }
}
