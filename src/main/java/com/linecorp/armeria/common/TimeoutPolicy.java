/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

/**
 * Determines when an invocation should time out.
 */
@FunctionalInterface
public interface TimeoutPolicy {

    /**
     * Creates a new {@link TimeoutPolicy} that times out an invocation after a fixed amount of time.
     *
     * @param timeout a positive value to enable timeout.
     *                zero to disable timeout.
     *
     * @throws IllegalArgumentException if the specified {@code timeout} is negative
     */
    static TimeoutPolicy ofFixed(Duration timeout) {
        requireNonNull(timeout, "timeout");

        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout: " + timeout + " (expected: >= 0)");
        }

        if (timeout.isZero()) {
            return disabled();
        }

        return new FixedTimeoutPolicy(timeout);
    }

    /**
     * Returns a singleton instance of a {@link TimeoutPolicy} that disables timeout.
     */
    static TimeoutPolicy disabled() {
        return DisabledTimeoutPolicy.INSTANCE;
    }

    /**
     * Creates a new {@link TimeoutPolicy} decorated with the specified {@code decorator}.
     */
    default <T extends TimeoutPolicy, U extends TimeoutPolicy> TimeoutPolicy decorate(Function<T, U> decorator) {
        @SuppressWarnings("unchecked")
        final TimeoutPolicy newPolicy = decorator.apply((T) this);

        if (newPolicy != null) {
            return newPolicy;
        } else {
            return this;
        }
    }

    /**
     * Determines the timeout of the invocation associated with the specified {@link ServiceInvocationContext}.
     *
     * @return the number of milliseconds to apply timeout to the invocation.
     *         {@code 0} to disable timeout for the invocation.
     */
    long timeout(ServiceInvocationContext ctx);
}
