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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.client.ClientFactoryBuilder.MIN_MAX_CONNECTION_AGE_MILLIS;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating a {@link MaxConnectionAgeConfig}.
 */
@UnstableApi
public final class MaxConnectionAgeConfigBuilder {

    private final long maxConnectionAgeMillis;
    private long replacementLeadTimeMillis;

    MaxConnectionAgeConfigBuilder(long maxConnectionAgeMillis) {
        checkArgument(maxConnectionAgeMillis >= MIN_MAX_CONNECTION_AGE_MILLIS || maxConnectionAgeMillis == 0,
                      "maxConnectionAgeMillis: %s (expected: >= %s or == 0)",
                      maxConnectionAgeMillis, MIN_MAX_CONNECTION_AGE_MILLIS);
        this.maxConnectionAgeMillis = maxConnectionAgeMillis;
    }

    /**
     * Sets how long before a connection reaches its max age the client opens a replacement connection to the
     * same endpoint. See {@link #replacementLeadTimeMillis(long)} for details.
     */
    public MaxConnectionAgeConfigBuilder replacementLeadTime(Duration replacementLeadTime) {
        return replacementLeadTimeMillis(
                requireNonNull(replacementLeadTime, "replacementLeadTime").toMillis());
    }

    /**
     * Sets how long before a connection reaches its max age the client opens a replacement connection to the
     * same endpoint, in millis. New requests move to the replacement once it is connected, and the old
     * connection still closes at its max age, so a request does not have to wait for a new connection when
     * the old one retires. Set it to cover the time to establish a connection, including DNS resolution, the
     * TLS handshake and the HTTP/2 preface. {@code 0}, the default, disables the replacement.
     *
     * <p>The replacement is best effort:
     * <ul>
     *   <li>Only a connection that has a request in progress, or sent a request within the idle timeout
     *       (at any time if the idle timeout is disabled), is replaced. An idle connection, including one kept
     *       open only by PINGs, and a replacement that never served a request are left to close at their max
     *       age.</li>
     *   <li>For HTTP/2, a connection attempt already in progress for the same endpoint is shared instead of
     *       opening another connection.</li>
     *   <li>If the replacement is not connected by the max age, the old connection still closes, and
     *       later requests wait for a new connection as they do without the replacement.</li>
     *   <li>WebSocket connections are not replaced.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified {@code replacementLeadTimeMillis} is negative.
     */
    public MaxConnectionAgeConfigBuilder replacementLeadTimeMillis(long replacementLeadTimeMillis) {
        checkArgument(replacementLeadTimeMillis >= 0, "replacementLeadTimeMillis: %s (expected: >= 0)",
                      replacementLeadTimeMillis);
        this.replacementLeadTimeMillis = replacementLeadTimeMillis;
        return this;
    }

    /**
     * Returns a newly-created {@link MaxConnectionAgeConfig} based on the properties of this builder.
     *
     * @throws IllegalStateException if the replacement lead time is greater than the max connection age.
     */
    public MaxConnectionAgeConfig build() {
        checkState(replacementLeadTimeMillis <= maxConnectionAgeMillis,
                   "replacementLeadTimeMillis: %s (expected: <= maxConnectionAgeMillis (%s))",
                   replacementLeadTimeMillis, maxConnectionAgeMillis);
        return new MaxConnectionAgeConfig(maxConnectionAgeMillis, replacementLeadTimeMillis);
    }
}
