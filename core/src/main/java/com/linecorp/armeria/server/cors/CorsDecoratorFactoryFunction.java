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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;

/**
 * A factory which creates a {@link CorsService} decorator when only one {@link CorsDecorator} is added.
 */
public final class CorsDecoratorFactoryFunction implements DecoratorFactoryFunction<CorsDecorator> {

    @Override
    public Function<Service<HttpRequest, HttpResponse>, ? extends Service<HttpRequest, HttpResponse>>
    newDecorator(CorsDecorator parameter) {
        requireNonNull(parameter, "parameter");
        final CorsServiceBuilder cb = CorsServiceBuilder.forOrigins(parameter.origins());
        cb.firstPolicyBuilder.setConfig(parameter);
        return cb.newDecorator();
    }
}
