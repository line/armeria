/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.internal.common.DecoratorAndOrder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;

/**
 * Specifies a {@link DecoratingHttpServiceFunction} class which handles an {@link HttpRequest} before invoking
 * an annotated service method.
 */
@Repeatable(Decorators.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Decorator {

    /**
     * {@link DecoratingHttpServiceFunction} implementation type. The specified class must have an accessible
     * default constructor.
     */
    Class<? extends DecoratingHttpServiceFunction> value();

    /**
     * The order of decoration, where a {@link Decorator} of lower value will be applied first.
     */
    int order() default DecoratorAndOrder.DEFAULT_ORDER;
}
