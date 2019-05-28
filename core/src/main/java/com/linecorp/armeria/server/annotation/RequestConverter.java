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

import com.linecorp.armeria.common.AggregatedHttpRequest;

/**
 * Specifies a {@link RequestConverterFunction} class which converts an {@link AggregatedHttpRequest} to
 * an object.
 *
 * <p>It can be specified on a class, a method and a parameter in an annotated service.
 * Its scope is determined by where it is specified, e.g.
 * <pre>{@code
 * > @RequestConverter(AliceConverter.class)
 * > @RequestConverter(BobConverter.class)
 * > public class MyService {
 * >
 * >     @Get("/general")
 * >     @RequestConverter(CarolConverter.class)
 * >     public HttpResponse general(Alice a, Bob b, Carol c) {
 * >         // Try CarolConverter, AliceConverter and BobConverter in order, for converting each parameter.
 * >     }
 * >
 * >     @Get("/special")
 * >     public HttpResponse special(@RequestConverter(SuperAliceConverter.class) Alice a, Bob b) {
 * >         // Try SuperAliceConverter, AliceConverter and BobConverter in order, for converting parameter 'a'.
 * >         // Try AliceConverter and BobConverter in order, for converting parameter 'b'.
 * >     }
 * > }
 * }</pre>
 * @see RequestConverterFunction
 * @see RequestObject
 */
@Repeatable(RequestConverters.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.TYPE,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD
})
public @interface RequestConverter {

    /**
     * {@link RequestConverterFunction} implementation type. The specified class must have an accessible
     * default constructor.
     */
    Class<? extends RequestConverterFunction> value();
}
