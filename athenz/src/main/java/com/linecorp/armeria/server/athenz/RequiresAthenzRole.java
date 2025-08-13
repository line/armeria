/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.athenz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;

/**
 * A {@link Decorator} which allows a request from a user granted the specified Athenz role.
 *
 * <p>Example:
 * <pre>{@code
 * class MyService {
 *   // 1. Decorate the method with `RequiresAthenzRole` to check Athenz role.
 *   @RequiresAthenzRole(resource = "user", action = "get")
 *   @ProducesJson
 *   @Get("/user")
 *   public CompletableFuture<User> getUser() {
 *      ...
 *   }
 * }
 *
 * // 2. Create a `ZtsBaseClient` and `AthenzServiceDecoratorFactory` to use Athenz.
 * ZtsBaseClient ztsBaseClient =
 *   ZtsBaseClient
 *     .builder("https://athenz.example.com:8443/zts/v1")
 *     .keyPair("/var/lib/athenz/service.key.pem", "/var/lib/athenz/service.cert.pem")
 *     .build();
 * final AthenzServiceDecoratorFactory athenzDecoratorFactory =
 *   AthenzServiceDecoratorFactory
 *     .builder(ztsBaseClient)
 *     .policyConfig(new AthenzPolicyConfig("my-domain"))
 *     .build();
 *
 * // 3. Create a `DependencyInjector` with the `AthenzServiceDecoratorFactory`
 * //    and set it to the server. `AthenzServiceDecoratorFactory` is required to
 * //    create the `RequiresAthenzRole` decorator.
 * final DependencyInjector di =
 *   DependencyInjector.ofSingletons(athenzDecoratorFactory)
 *                     .orElse(DependencyInjector.ofReflective());
 * serverBuilder.dependencyInjector(di, true);
 * }</pre>
 */
@UnstableApi
@DecoratorFactory(AthenzServiceDecoratorFactory.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface RequiresAthenzRole {

    /**
     * The required Athenz resource.
     */
    String resource();

    /**
     * The required Athenz action.
     */
    String action();

    /**
     * The required {@link TokenType}.
     */
    TokenType[] tokenType() default {
            TokenType.ACCESS_TOKEN, TokenType.ATHENZ_ROLE_TOKEN, TokenType.YAHOO_ROLE_TOKEN
    };

    /**
     * A special parameter in order to specify the order of a {@link Decorator}.
     */
    int order() default 0;
}
