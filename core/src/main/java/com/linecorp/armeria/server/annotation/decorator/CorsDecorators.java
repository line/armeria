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
package com.linecorp.armeria.server.annotation.decorator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.cors.CorsDecoratorsFactoryFunction;

/**
 * The containing annotation type for {@link CorsDecorator}.
 */
@DecoratorFactory(CorsDecoratorsFactoryFunction.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface CorsDecorators {
    /**
     * An array of {@link CorsDecorator}s.
     */
    CorsDecorator[] value();

    /**
     * Specifies that a CORS request should be rejected if it's invalid before being processed further.
     *
     * <p>CORS headers are set after a request is processed. This may not always be desired
     * and this setting will check that the origin is valid and if it is not valid no
     * further processing will take place, and an error will be returned to the calling client.
     */
    boolean shortCircuit() default false;
}
