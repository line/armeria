/*
 * Copyright 2018 LINE Corporation
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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Response;

/**
 * Determines whether a {@link Response} should be reported as a success or a failure to a
 * {@link CircuitBreaker} using the content of a {@link Response}. If you just need the HTTP headers
 * to make a decision, use {@link CircuitBreakerStrategy} for efficiency.
 *
 * @param <T> the response type
 *
 * @deprecated Use {@link CircuitBreakerRuleWithContent}.
 */
@Deprecated
@FunctionalInterface
public interface CircuitBreakerStrategyWithContent<T extends Response> {

    /**
     * Returns a {@link CompletionStage} that contains {@code true}, {@code false} or
     * {@code null} according to the specified {@link Response}.
     * If {@code true} is returned, {@link CircuitBreaker#onSuccess()} is called so that the
     * {@link CircuitBreaker} increases its success count and uses it to make a decision
     * to close or open the circuit. If {@code false} is returned, it works the other way around.
     * If {@code null} is returned, the {@link CircuitBreaker} ignores it.
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param response the {@link Response} from the server
     *
     * @deprecated Use {@link CircuitBreakerRuleWithContent#shouldReportAsSuccess(
     *             ClientRequestContext, Response, Throwable)}.
     */
    @Deprecated
    CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx, T response);
}
