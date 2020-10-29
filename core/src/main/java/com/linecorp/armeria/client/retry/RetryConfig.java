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

/**
 * Holds retry config used by a {@link RetryingClient}.
 */
public final class RetryConfig {
    private final int maxTotalAttempts;
    private final long responseTimeoutMillisForEachAttempt;

    /**
     * Returns a new {@link RetryConfigBuilder} with the default values from Flags.
     */
    public static RetryConfigBuilder builder() {
        return new RetryConfigBuilder();
    }

    /**
     * Returns config's maxTotalAttempt.
     */
    public int maxTotalAttempts() {
        return maxTotalAttempts;
    }

    /**
     * Returns config's responseTimeoutMillisForEachAttempt.
     */
    public long responseTimeoutMillisForEachAttempt() {
        return responseTimeoutMillisForEachAttempt;
    }

    RetryConfig(int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        checkArgument(
                maxTotalAttempts > 0,
                "maxTotalAttempts: %s (expected: > 0)",
                maxTotalAttempts);
        this.maxTotalAttempts = maxTotalAttempts;
        checkArgument(
                responseTimeoutMillisForEachAttempt >= 0,
                "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
    }
}
