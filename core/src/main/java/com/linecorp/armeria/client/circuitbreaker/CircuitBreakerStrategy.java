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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Response;

/**
 * Determines whether a {@link Response} should be reported as a success or a failure to a
 * {@link CircuitBreaker}.
 *
 * @param <T> the response type
 */
@FunctionalInterface
public interface CircuitBreakerStrategy<T extends Response> {

    /**
     * Returns the {@link CircuitBreakerStrategy} that determines a {@link Response} as successful
     * when its {@link HttpStatus} is not {@link HttpStatusClass#SERVER_ERROR} and there was no
     * {@link Exception} raised.
     */
    static CircuitBreakerStrategy<HttpResponse> onServerErrorStatus() {
        return onStatus(
                (status, thrown) -> status != null && status.codeClass() != HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Returns the {@link CircuitBreakerStrategy} that determines a {@link Response} as successful
     * using the specified {@link BiFunction}.
     *
     * @param function the {@link BiFunction} that returns {@code true}, {@code false} or
     *                 {@code null} according to the {@link HttpStatus} and {@link Throwable}. If {@code true}
     *                 is returned, {@link CircuitBreaker#onSuccess()} is called so that the
     *                 {@link CircuitBreaker} increases its success count and use it to make a decision to
     *                 close or open the switch. If {@code false} is returned, it works the other way around.
     *                 If {@code null} is returned, the {@link CircuitBreaker} ignores it.
     */
    static CircuitBreakerStrategy<HttpResponse> onStatus(
            BiFunction<HttpStatus, Throwable, Boolean> function) {
        return new HttpStatusBasedCircuitBreakerStrategy(function);
    }

    /**
     * Returns a {@link CompletionStage} that contains {@code true}, {@code false} or
     * {@code null} according to the specified {@link Response}. If {@code true} is returned,
     * {@link CircuitBreaker#onSuccess()} is called so that the {@link CircuitBreaker} increases its success
     * count and use it to make a decision to close or open the switch. If {@code false} is returned, it works
     * the other way around. If {@code null} is returned, the {@link CircuitBreaker} ignores it.
     */
    CompletionStage<Boolean> shouldReportAsSuccess(T response);
}
