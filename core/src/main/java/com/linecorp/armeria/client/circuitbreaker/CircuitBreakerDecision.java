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

package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.common.Response;

/**
 * A {@link CircuitBreakerDecision} that determines a {@link Response} as
 * a {@link #success()} or {@link #failure()}, or {@link #ignore()}s a {@link Response}.
 * {@link #next()} skips a {@link CircuitBreakerRule} and will lookup
 * next {@link CircuitBreakerRule}s.
 */
public final class CircuitBreakerDecision {

    private static final CircuitBreakerDecision SUCCESS = new CircuitBreakerDecision();
    private static final CircuitBreakerDecision FAILURE = new CircuitBreakerDecision();
    private static final CircuitBreakerDecision IGNORE = new CircuitBreakerDecision();
    private static final CircuitBreakerDecision NEXT = new CircuitBreakerDecision();

    /**
     * Returns a {@link CircuitBreakerDecision} that reports a {@link Response} as a success.
     */
    public static CircuitBreakerDecision success() {
        return SUCCESS;
    }

    /**
     * Returns a {@link CircuitBreakerDecision} that reports a {@link Response} as a failure.
     */
    public static CircuitBreakerDecision failure() {
        return FAILURE;
    }

    /**
     * Returns a {@link CircuitBreakerDecision} that skips the current {@link CircuitBreakerRule} and
     * tries to evaluate a next {@link CircuitBreakerRule}.
     */
    public static CircuitBreakerDecision next() {
        return NEXT;
    }

    /**
     * Returns a {@link CircuitBreakerDecision} that ignores a {@link Response} and does not count as a success
     * nor failure.
     */
    public static CircuitBreakerDecision ignore() {
        return IGNORE;
    }

    private CircuitBreakerDecision() {}

    @Override
    public String toString() {
        if (this == SUCCESS) {
            return "CircuitBreakerDecision(SUCCESS)";
        } else if (this == FAILURE) {
            return "CircuitBreakerDecision(FAILURE)";
        } else if (this == NEXT) {
            return "CircuitBreakerDecision(NEXT)";
        } else if (this == IGNORE) {
            return "CircuitBreakerDecision(IGNORE)";
        } else {
            return super.toString();
        }
    }
}
