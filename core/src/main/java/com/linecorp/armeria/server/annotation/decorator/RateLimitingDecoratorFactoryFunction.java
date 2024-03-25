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
package com.linecorp.armeria.server.annotation.decorator;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.internal.server.annotation.DefaultValues;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.throttling.ThrottlingService;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;

/**
 * A factory which creates a {@link ThrottlingService} decorator with a
 * {@link ThrottlingStrategy#rateLimiting(double, String)}.
 */
public final class RateLimitingDecoratorFactoryFunction
        implements DecoratorFactoryFunction<RateLimitingDecorator> {
    /**
     * Creates a new decorator with the specified {@link RateLimitingDecorator}.
     */
    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(RateLimitingDecorator parameter) {
        final String name = parameter.name();
        final ThrottlingStrategy<HttpRequest> throttlingStrategy;
        if (DefaultValues.isSpecified(name)) {
            throttlingStrategy = ThrottlingStrategy.rateLimiting(parameter.value());
        } else {
            throttlingStrategy = ThrottlingStrategy.rateLimiting(parameter.value(), name);
        }
        return ThrottlingService.newDecorator(throttlingStrategy);
    }
}
