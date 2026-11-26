/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The client-side max age of a connection, and how a connection is replaced before it reaches that age.
 *
 * @see ClientFactoryBuilder#maxConnectionAge(MaxConnectionAgeConfig)
 */
@UnstableApi
public final class MaxConnectionAgeConfig {

    /**
     * Returns a new {@link MaxConnectionAgeConfigBuilder} with the specified max connection age.
     *
     * @param maxConnectionAge the maximum connection age. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAge} is smaller than
     *                                  {@value ClientFactoryBuilder#MIN_MAX_CONNECTION_AGE_MILLIS}
     *                                  milliseconds.
     */
    public static MaxConnectionAgeConfigBuilder builder(Duration maxConnectionAge) {
        return builder(requireNonNull(maxConnectionAge, "maxConnectionAge").toMillis());
    }

    /**
     * Returns a new {@link MaxConnectionAgeConfigBuilder} with the specified max connection age in millis.
     *
     * @param maxConnectionAgeMillis the maximum connection age in millis. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAgeMillis} is smaller than
     *                                  {@value ClientFactoryBuilder#MIN_MAX_CONNECTION_AGE_MILLIS}
     *                                  milliseconds.
     */
    public static MaxConnectionAgeConfigBuilder builder(long maxConnectionAgeMillis) {
        return new MaxConnectionAgeConfigBuilder(maxConnectionAgeMillis);
    }

    private final long maxConnectionAgeMillis;
    private final long replacementLeadTimeMillis;

    MaxConnectionAgeConfig(long maxConnectionAgeMillis, long replacementLeadTimeMillis) {
        this.maxConnectionAgeMillis = maxConnectionAgeMillis;
        this.replacementLeadTimeMillis = replacementLeadTimeMillis;
    }

    /**
     * Returns the maximum allowed age of a connection in millis. {@code 0} means unlimited.
     */
    public long maxConnectionAgeMillis() {
        return maxConnectionAgeMillis;
    }

    /**
     * Returns how long before a connection reaches its max age the client opens a replacement connection to
     * the same endpoint, in millis. {@code 0} means the replacement is disabled.
     *
     * @see MaxConnectionAgeConfigBuilder#replacementLeadTimeMillis(long)
     */
    public long replacementLeadTimeMillis() {
        return replacementLeadTimeMillis;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MaxConnectionAgeConfig)) {
            return false;
        }
        final MaxConnectionAgeConfig that = (MaxConnectionAgeConfig) o;
        return maxConnectionAgeMillis == that.maxConnectionAgeMillis &&
               replacementLeadTimeMillis == that.replacementLeadTimeMillis;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(maxConnectionAgeMillis) * 31 + Long.hashCode(replacementLeadTimeMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("maxConnectionAgeMillis", maxConnectionAgeMillis)
                          .add("replacementLeadTimeMillis", replacementLeadTimeMillis)
                          .toString();
    }
}
