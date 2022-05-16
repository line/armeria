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
import java.util.function.IntSupplier;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.SettableIntSupplier;

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
     * Returns a newly-created {@link ConcurrencyLimit} with the specified {@link IntSupplier}.
     * {@link IntSupplier#getAsInt()} might be frequently called, so please consider using
     * {@link SettableIntSupplier} if supplying the value needs heavy computation. For example:
     * <pre>{@code
     * ConcurrencyLimit limit = ConcurrencyLimit.of(new DynamicLimit());
     *
     * class DynamicLimit implements IntSupplier {
     *     private final SettableIntSupplier settableIntSupplier = SettableIntSupplier.of(16);
     *
     *     DynamicLimit() {
     *         LimitChangeListener<Integer> listener = ...
     *         listener.addListener(updatedValue -> settableIntSupplier.set(updatedValue));
     *     }
     *
     *     @Override
     *     public int getAsInt() {
     *         return settableIntSupplier.getAsInt();
     *     }
     * }}</pre>
     *
     * <p>Note that {@link IntSupplier} must supply a positive number. Otherwise, all requests will end up
     * in pending state.
     */
    @UnstableApi
    static ConcurrencyLimit of(IntSupplier maxConcurrency) {
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
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@link IntSupplier}.
     * {@link IntSupplier#getAsInt()} might be frequently called, so please consider using
     * {@link SettableIntSupplier} if supplying the value needs heavy computation. For example:
     * <pre>{@code
     * ConcurrencyLimitBuilder builder = ConcurrencyLimit.builder(new DynamicLimit());
     *
     * class DynamicLimit implements IntSupplier {
     *     private final SettableIntSupplier settableIntSupplier = SettableIntSupplier.of(16);
     *
     *     DynamicLimit() {
     *         LimitChangeListener<Integer> listener = ...
     *         listener.addListener(updatedValue -> settableIntSupplier.set(updatedValue));
     *     }
     *
     *     @Override
     *     public int getAsInt() {
     *         return settableIntSupplier.getAsInt();
     *     }
     * }}</pre>
     *
     * <p>Note that {@link IntSupplier} must supply a positive number. Otherwise, all requests will end up
     * in pending state.
     */
    @UnstableApi
    static ConcurrencyLimitBuilder builder(IntSupplier maxConcurrency) {
        requireNonNull(maxConcurrency, "maxConcurrency");
        return new ConcurrencyLimitBuilder(maxConcurrency);
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
