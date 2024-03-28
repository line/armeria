/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.cors;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;

/**
 * A factory which creates a {@link CorsService} decorator when only one {@link CorsDecorator} is added.
 */
public final class CorsDecoratorFactoryFunction implements DecoratorFactoryFunction<CorsDecorator> {

    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(CorsDecorator parameter) {
        requireNonNull(parameter, "parameter");
        final CorsServiceBuilder cb;
        if (parameter.origins().length > 0) {
            cb = CorsService.builder(parameter.origins());
        } else if (!parameter.originRegex().isEmpty()) {
            cb = CorsService.builderForOriginRegex(parameter.originRegex());
        } else {
            throw new IllegalArgumentException("Either origins or originRegex must be configured");
        }
        cb.firstPolicyBuilder.setConfig(parameter);

        final Function<? super HttpService, CorsService> decorator = cb.newDecorator();
        return service -> {
            if (service.as(CorsService.class) != null) {
                return service;
            } else {
                return decorator.apply(service);
            }
        };
    }
}
