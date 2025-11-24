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

import org.springframework.web.reactive.function.client.ExchangeStrategies;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating a new instance of {@link ArmeriaHttpExchangeAdapter}.
 */
@UnstableApi
public final class ArmeriaHttpExchangeAdapterBuilder {

    private final WebClient webClient;
    private ExchangeStrategies exchangeStrategies = ExchangeStrategies.withDefaults();
    @Nullable
    private StatusHandler statusHandler;

    ArmeriaHttpExchangeAdapterBuilder(WebClient webClient) {
        this.webClient = requireNonNull(webClient, "webClient");
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
     * Adds the {@link StatusHandler} that converts specific error {@link HttpStatus}s to a {@link Throwable}
     * to be propagated downstream instead of the response.
     */
    public ArmeriaHttpExchangeAdapterBuilder statusHandler(StatusHandler statusHandler) {
        requireNonNull(statusHandler, "statusHandler");
        if (this.statusHandler == null) {
            this.statusHandler = statusHandler;
        } else {
            this.statusHandler = this.statusHandler.orElse(statusHandler);
        }
        return this;
    }

    /**
     * Returns a newly-created {@link ArmeriaHttpExchangeAdapter} based on the properties of this builder.
     */
    public ArmeriaHttpExchangeAdapter build() {
        return new ArmeriaHttpExchangeAdapter(webClient, exchangeStrategies, statusHandler);
    }
}
