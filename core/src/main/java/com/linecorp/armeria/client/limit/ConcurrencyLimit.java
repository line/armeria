/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Integer.MAX_VALUE;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Limits the concurrency of client requests.
 */
public interface ConcurrencyLimit {
    /**
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@code maxConcurrency}.
     *
     * @param maxConcurrency the maximum number of concurrent active requests,
     *                       specify {@code 0} to disable the limit.
     */
    static ConcurrencyLimitBuilder builder(int maxConcurrency) {
        checkArgument(maxConcurrency >= 0,
                      "maxConcurrency: %s (expected: >= 0)", maxConcurrency);
        return new ConcurrencyLimitBuilder(maxConcurrency == MAX_VALUE ? 0 : maxConcurrency);
    }

    /**
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@code maxConcurrency}.
     *
     * @param maxConcurrency the maximum number of concurrent active requests,
     *                       specify {@code 0} to disable the limit.
     */
    static ConcurrencyLimitBuilder of(int maxConcurrency) {
        return builder(maxConcurrency);
    }

    /**
     * Acquire a {@link SafeCloseable}, asynchronously.
     *
     * <p>Make sure to call {@link SafeCloseable#close()} once the operation guarded by the permit
     * completes successfully or with error
     *
     * @return the {@link SafeCloseable}
     */
    CompletableFuture<SafeCloseable> acquire(ClientRequestContext ctx);

    /**
     * Returns the total number of acquired permits or -1 in case its value is unknown.
     */
    int acquiredPermits();

    /**
     * Returns the total number of available permits -1 in case its value is unknown.
     */
    int availablePermits();
}
