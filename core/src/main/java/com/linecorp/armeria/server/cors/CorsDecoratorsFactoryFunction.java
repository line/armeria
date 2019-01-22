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

import static com.google.common.base.Preconditions.checkState;

import java.util.Arrays;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorators;

public final class CorsDecoratorsFactoryFunction implements DecoratorFactoryFunction<CorsDecorators> {

    private static final Logger logger = LoggerFactory.getLogger(CorsDecoratorsFactoryFunction.class);

    /**
     * Creates a new decorator with the specified {@code parameter}.
     */
    @Override
    public Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(CorsDecorators parameter) {
        ensureValidConfig(parameter);

        final CorsDecorator[] policies = parameter.value();
        final CorsDecorator corsDecorator = policies[0];
        final CorsServiceBuilder cb = CorsServiceBuilder.forOrigins(corsDecorator.origins());
        if (parameter.shortCircuit()) {
            cb.shortCircuit();
        }
        cb.setConfig(corsDecorator);
        for (int i = 1; i < policies.length; i++) {
            final CorsPolicyBuilder builder = new CorsPolicyBuilder(policies[i].origins());
            builder.setConfig(policies[i]);
            cb.addPolicy(builder.build());
        }
        return cb.newDecorator();
    }

    private static void ensureValidConfig(CorsDecorators conf) {
        checkState(conf.value().length > 0, "value() should not be empty.");
        final boolean anyOrigin = Arrays.stream(conf.value()).anyMatch(
                c -> Arrays.asList(c.origins()).contains("*"));
        final CorsDecorator[] policies = conf.value();
        checkState(!anyOrigin || (policies.length == 1 && policies[0].origins().length == 1),
                   "the policy that support any origin (*) has been already included." +
                   " You cannot have an additional policy or origin.");
        checkState(Arrays.stream(policies).noneMatch(c -> c.origins().length == 0),
                   "origins should not be empty.");
        if (Arrays.stream(policies).anyMatch(CorsDecorator::shortCircuit)) {
            logger.warn("CorsDecorator.shortCircuit will be ignored with CorsDecorators." +
                        " Sets CorsDecorators.shortCircuit to be true instead if you want to use it.");
        }
    }
}
