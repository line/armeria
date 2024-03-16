/*
 * Copyright 2024 LINE Corporation
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
 *
 */

package com.linecorp.armeria.spring.client;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Predicate;

import org.springframework.web.reactive.function.client.ExchangeStrategies;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

import reactor.core.publisher.Mono;

/**
 * A builder for creating a new instance of {@link ArmeriaHttpExchangeAdapter}.
 */
public final class ArmeriaHttpExchangeAdapterBuilder {

    private final WebClient webClient;
    private ExchangeStrategies exchangeStrategies = ExchangeStrategies.withDefaults();
    private final ImmutableList.Builder<Map.Entry<Predicate<HttpStatus>, Mono<? extends Throwable>>>
            statusHandlers = ImmutableList.builder();

    ArmeriaHttpExchangeAdapterBuilder(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Sets the {@link ExchangeStrategies} that overrides the
     * {@linkplain ExchangeStrategies#withDefaults() default strategies}.
     */
    public ArmeriaHttpExchangeAdapterBuilder exchangeStrategies(ExchangeStrategies exchangeStrategies) {
        requireNonNull(exchangeStrategies, "exchangeStrategies");
        this.exchangeStrategies = exchangeStrategies;
        return this;
    }

    /**
     * Adds a status handler that handles the specified {@link HttpStatus} with the given exception function.
     */
    public ArmeriaHttpExchangeAdapterBuilder statusHandler(HttpStatus httpStatus,
                                                           Mono<? extends Throwable> exceptionFunction) {
        requireNonNull(httpStatus, "httpStatus");
        return statusHandler(status -> status.equals(httpStatus), exceptionFunction);
    }

    /**
     * Adds a status handler that handles the specified {@link HttpStatusClass} with the given exception
     * function.
     */
    public ArmeriaHttpExchangeAdapterBuilder statusHandler(HttpStatusClass httpStatusClass,
                                                           Mono<? extends Throwable> exceptionFunction) {
        requireNonNull(httpStatusClass, "httpStatusClass");
        return statusHandler(status -> status.codeClass() == httpStatusClass, exceptionFunction);
    }

    /**
     * Adds a status handler that handles an arbitrary {@link HttpStatus} with the given exception function.
     */
    public ArmeriaHttpExchangeAdapterBuilder statusHandler(Predicate<HttpStatus> predicate,
                                                           Mono<? extends Throwable> exceptionFunction) {
        requireNonNull(predicate, "predicate");
        requireNonNull(exceptionFunction, "exceptionFunction");
        statusHandlers.add(Maps.immutableEntry(predicate, exceptionFunction));
        return this;
    }

    /**
     * Returns a newly-created {@link ArmeriaHttpExchangeAdapter} based on the properties of this builder.
     */
    public ArmeriaHttpExchangeAdapter build() {
        return new ArmeriaHttpExchangeAdapter(webClient, exchangeStrategies, statusHandlers.build());
    }
}
