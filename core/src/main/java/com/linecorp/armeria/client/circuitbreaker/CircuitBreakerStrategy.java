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
import java.util.function.BiFunction;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * Determines whether a {@link Response} should be reported as a success or a failure to a
 * {@link CircuitBreaker}. If you need to determine whether the request was successful by looking into the
 * {@link Response} content, use {@link CircuitBreakerStrategyWithContent}.
 *
 * @deprecated Use {@link CircuitBreakerRule}.
 */
@Deprecated
@FunctionalInterface
public interface CircuitBreakerStrategy {

    /**
     * Returns the {@link CircuitBreakerStrategy} that determines a {@link Response} as successful
     * when its {@link HttpStatus} is not {@link HttpStatusClass#SERVER_ERROR} and there was no
     * {@link Exception} raised.
     *
     * @deprecated Use {@link CircuitBreakerRule#builder()},
     *             {@link CircuitBreakerRuleBuilder#onServerErrorStatus()}
     *             and {@link CircuitBreakerRuleBuilder#onException()}
     *             with {@link CircuitBreakerRuleBuilder#thenFailure()}.
     *             For example:
     *             <pre>{@code
     *             CircuitBreakerRule.builder()
     *                               .onServerErrorStatus()
     *                               .onException()
     *                               .thenFailure();
     *             }</pre>
     */
    @Deprecated
    static CircuitBreakerStrategy onServerErrorStatus() {
        return onStatus((status, thrown) -> status != null && !status.isServerError());
    }

    /**
     * Returns the {@link CircuitBreakerStrategy} that determines a {@link Response} as successful
     * using the specified {@link BiFunction}.
     *
     * @param function the {@link BiFunction} that returns {@code true}, {@code false} or
     *                 {@code null} according to the {@link HttpStatus} and {@link Throwable}. If {@code true}
     *                 is returned, {@link CircuitBreaker#onSuccess()} is called so that the
     *                 {@link CircuitBreaker} increases its success count and uses it to make a decision to
     *                 close or open the circuit. If {@code false} is returned, it works the other way around.
     *                 If {@code null} is returned, the {@link CircuitBreaker} ignores it.
     *
     * @deprecated Use {@link CircuitBreakerRule#builder()},
     *             {@link CircuitBreakerRuleBuilder#onStatus(Predicate)}
     *             and {@link CircuitBreakerRuleBuilder#onException(Predicate)}.
     */
    @Deprecated
    static CircuitBreakerStrategy onStatus(BiFunction<HttpStatus, Throwable, Boolean> function) {
        return new HttpStatusBasedCircuitBreakerStrategy(function);
    }

    /**
     * Returns a {@link CompletionStage} that contains {@code true}, {@code false} or
     * {@code null} which indicates a {@link Response} is successful or not. If {@code true} is returned,
     * {@link CircuitBreaker#onSuccess()} is called so that the {@link CircuitBreaker} increases its success
     * count and uses it to make a decision to close or open the circuit. If {@code false} is returned, it works
     * the other way around. If {@code null} is returned, the {@link CircuitBreaker} ignores it.
     * To retrieve the {@link ResponseHeaders}, you can use the specified {@link ClientRequestContext}:
     *
     * <pre>{@code
     * CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx, @Nullable Throwable cause) {
     *     if (cause != null) {
     *         return CompletableFuture.completedFuture(false);
     *     }
     *
     *     ResponseHeaders responseHeaders = ctx.log().responseHeaders();
     *     if (responseHeaders.status().codeClass() == HttpStatusClass.SERVER_ERROR) {
     *         return CompletableFuture.completedFuture(false);
     *     }
     *     ...
     * }
     * }</pre>
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param cause the {@link Throwable} which is raised while sending a request. {@code null} if there's no
     *              exception.
     *
     * @deprecated Use {@link CircuitBreakerRule#shouldReportAsSuccess(ClientRequestContext, Throwable)}.
     */
    @Deprecated
    CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx, @Nullable Throwable cause);
}
