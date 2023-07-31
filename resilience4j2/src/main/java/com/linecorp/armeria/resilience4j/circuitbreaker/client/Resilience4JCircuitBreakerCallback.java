/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.resilience4j.circuitbreaker.FailedCircuitBreakerDecisionException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

final class Resilience4JCircuitBreakerCallback implements CircuitBreakerCallback {

    private final CircuitBreaker circuitBreaker;
    private final long startTimestamp;

    Resilience4JCircuitBreakerCallback(CircuitBreaker circuitBreaker, long startTimestamp) {
        this.circuitBreaker = requireNonNull(circuitBreaker, "circuitBreaker");
        this.startTimestamp = startTimestamp;
    }

    @Override
    public void onSuccess(RequestContext ctx) {
        final long duration = circuitBreaker.getCurrentTimestamp() - startTimestamp;
        circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
    }

    @Override
    public void onFailure(RequestContext ctx, @Nullable Throwable cause) {
        final long duration = circuitBreaker.getCurrentTimestamp() - startTimestamp;

        if (cause == null) {
            final RequestLog requestLog = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
            cause = requestLog != null ? requestLog.responseCause() : null;
        }
        if (cause == null) {
            cause = FailedCircuitBreakerDecisionException.of();
        }
        circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), cause);
    }
}
