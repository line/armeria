/*
 * Copyright 2022 LINE Corporation
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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.internal.common.BuiltInDependencyInjector;

/**
 * Indicates that an annotated class will be created and injected automatically even if the class
 * can't be resolved by the configured {@link DependencyInjector}
 * when the class is specified as an annotated service dependency by one of the following annotation.
 * <ul>
 *     <li>{@link RequestConverter#value()}</li>
 *     <li>{@link ResponseConverter#value()}</li>
 *     <li>{@link DecoratorFactory#value()}</li>
 *     <li>{@link ExceptionHandler#value()}</li>
 * </ul>
 *
 * The required dependencies are created as singleton object and must have a default
 * no-argument public constructor.
 *
 * <p>For example:<pre>{@code
 * @CreateIfMissing
 * public class FooDecoratorFactoryFunction implements DecoratorFactoryFunction<FooDecorator> {
 *     // The class has a default no-argument constructor.
 *
 *     @Override
 *     public Function<? super HttpService, ? extends HttpService> newDecorator(FooDecorator parameter) {
 *         return service -> service;
 *     }
 * }
 * }</pre>
 *
 * @see DependencyInjector
 * @see BuiltInDependencyInjector
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CreateIfMissing {
}
