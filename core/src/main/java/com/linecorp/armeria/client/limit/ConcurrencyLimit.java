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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Limits the concurrency of client requests.
 */
@UnstableApi
@FunctionalInterface
public interface ConcurrencyLimit {

    /**
     * Returns a newly-created {@link ConcurrencyLimit} with the specified {@code maxConcurrency}.
     *
     * @param maxConcurrency the maximum number of concurrent active requests.
     *                       Specify {@code 0} to disable the limit.
     */
    static ConcurrencyLimit of(int maxConcurrency) {
        return builder(maxConcurrency).build();
    }

    /**
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@code maxConcurrency}.
     *
     * @param maxConcurrency the maximum number of concurrent active requests.
     *                       Specify {@code 0} to disable the limit.
     */
    static ConcurrencyLimitBuilder builder(int maxConcurrency) {
        checkArgument(maxConcurrency >= 0,
                      "maxConcurrency: %s (expected: >= 0)", maxConcurrency);
        return new ConcurrencyLimitBuilder(maxConcurrency == MAX_VALUE ? 0 : maxConcurrency);
    }

    /**
     * Returns a newly-created {@link ConcurrencyLimit} with the specified {@code maxConcurrency}. For example:
     * <pre> {@code
     *     ConcurrencyLimit.builder(new DynamicLimit());
     *
     *     class DynamicLimit extends CachedValue<Integer> {
     *         public DynamicLimit() {
     *             super(defaultValue);
     *             dogma.watcher().watch((rev, value) -> {
     *                 set(Integer.parse(value));
     *             });
     *         }
     *     }
     * } </pre>
     *
     * @param maxConcurrency the supplier for maximum number of concurrent active requests.
     *                       It must be supplied a positive number in a very short time.
     *                       If it does not supply a positive number, all requests will be pending.
     */
    static ConcurrencyLimit of(Supplier<Integer> maxConcurrency) {
        return builder(maxConcurrency).build();
    }

    /**
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@code maxConcurrency}. For example:
     * <pre> {@code
     *     ConcurrencyLimit.builder(new DynamicLimit());
     *
     *     class DynamicLimit extends CachedValue<Integer> {
     *         public DynamicLimit() {
     *             super(defaultValue);
     *             dogma.watcher().watch((rev, value) -> {
     *                 set(Integer.parse(value));
     *             });
     *         }
     *     }
     * } </pre>
     *
     * @param maxConcurrency the supplier for maximum number of concurrent active requests.
     *                       It must be supplied a positive number in a very short time.
     *                       If it does not supply a positive number, all requests will be pending.
     */
    static ConcurrencyLimitBuilder builder(Supplier<Integer> maxConcurrency) {
        return new ConcurrencyLimitBuilder(requireNonNull(maxConcurrency, "maxConcurrency"));
    }

    /**
     * Acquires a {@link SafeCloseable} that allows you to execute a job under the limit.
     * The {@link SafeCloseable} must be closed after the job is done:
     *
     * <pre>{@code
     * ConcurrencyLimit limit = ...
     * limit.acquire(ctx).handle((permit, cause) -> {
     *     if (cause != null) {
     *         // Failed to acquire a permit.
     *         ...
     *     }
     *     // Execute your job.
     *     ...
     *     // Release the permit.
     *     permit.close();
     * });
     * }</pre>
     */
    CompletableFuture<SafeCloseable> acquire(ClientRequestContext ctx);
}
