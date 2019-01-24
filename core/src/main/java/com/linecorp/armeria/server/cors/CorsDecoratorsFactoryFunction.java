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
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorators;

/**
 * A factory which creates a {@link CorsService} decorator when two or more {@link CorsDecorator}s are added.
 */
public final class CorsDecoratorsFactoryFunction implements DecoratorFactoryFunction<CorsDecorators> {

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
        cb.firstPolicyBuilder.setConfig(corsDecorator);
        for (int i = 1; i < policies.length; i++) {
            final CorsPolicyBuilder builder = new CorsPolicyBuilder(policies[i].origins());
            builder.setConfig(policies[i]);
            cb.addPolicy(builder.build());
        }
        return cb.newDecorator();
    }

    private static void ensureValidConfig(CorsDecorators conf) {
        requireNonNull(conf, "conf");
        final CorsDecorator[] policies = conf.value();
        checkState(policies.length > 0, "value() should not be empty.");
        final boolean anyOrigin = Arrays.stream(policies).anyMatch(
                c -> Arrays.asList(c.origins()).contains("*"));
        checkState(!anyOrigin || (policies.length == 1 && policies[0].origins().length == 1),
                   "the policy that support any origin (*) has been already included." +
                   " You cannot have an additional policy or origin.");
        checkState(Arrays.stream(policies).noneMatch(c -> c.origins().length == 0),
                   "origins should not be empty.");
    }
}
