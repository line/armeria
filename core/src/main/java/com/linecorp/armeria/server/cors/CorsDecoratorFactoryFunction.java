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
import com.linecorp.armeria.server.annotation.Cors;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;

public final class CorsDecoratorFactoryFunction implements DecoratorFactoryFunction<CorsDecorator> {

    private static Logger logger = LoggerFactory.getLogger(CorsDecoratorFactoryFunction.class);

    /**
     * Creates a new decorator with the specified {@code parameter}.
     */
    @Override
    public Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(CorsDecorator parameter) {
        ensureValidConfig(parameter);

        final Cors[] policies = parameter.value();
        final boolean anyOrigin = isAnyOrigin(parameter);
        final Cors cors = policies[0];
        final CorsServiceBuilder cb =
                anyOrigin ? CorsServiceBuilder.forAnyOrigin() : CorsServiceBuilder.forOrigins(cors.origins());
        if (parameter.shortCircuit()) {
            cb.shortCircuit();
        }
        cb.setConfig(cors);
        for (int i = 1; i < policies.length; i++) {
            final CorsPolicyBuilder builder = new CorsPolicyBuilder(policies[i].origins());
            builder.setConfig(policies[i]);
            cb.addPolicy(builder.build());
        }
        return cb.newDecorator();
    }

    private static void ensureValidConfig(CorsDecorator conf) {
        checkState(conf.value().length > 0, "");
        final boolean anyOrigin = isAnyOrigin(conf);
        checkState(!anyOrigin || conf.value().length == 1, "");
        checkState(anyOrigin || Arrays.stream(conf.value()).noneMatch(c -> c.origins().length == 0),
                   "origins should not be empty.");
        if (Arrays.stream(conf.value()).anyMatch(Cors::shortCircuit)) {
            logger.warn("Shortcircuit will be ignored with seperate CORS Policy."
                        + " If you want to use shortCircuit, set CorsDecorator.shortCircuit to be true");
        }
    }

    private static boolean isAnyOrigin(CorsDecorator conf) {
        if (conf.anyOrigin()) {
            return true;
        }
        final Cors[] policies = conf.value();
        if (policies.length == 1 && policies[0].origins().length == 1) {
            return "*".equals(policies[0].origins()[0]);
        }
        return false;
    }
}
