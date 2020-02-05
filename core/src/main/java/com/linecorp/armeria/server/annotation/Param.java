/*
 * Copyright 2016 LINE Corporation
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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.internal.server.annotation.DefaultValues;

/**
 * Annotation for mapping a parameter of a request onto the following elements.
 *
 * <p>a parameter of an annotated service method</p>
 *
 * <p>or, a field of a request bean</p>
 *
 * <p>or, a constructor with only one parameter of a request bean</p>
 *
 * <p>or, a method with only one parameter of a request bean</p>
 *
 * <p>or, a parameter of a request bean constructor</p>
 *
 * <p>or, a parameter of a request bean method</p>
 *
 * <p>(See: {@link RequestConverter} and {@link RequestConverterFunction})</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR })
public @interface Param {

    /**
     * The name of the request parameter to bind to.
     * The path variable, the parameter name in a query string or a URL-encoded form data,
     * or the name of a multipart.
     */
    String value() default DefaultValues.UNSPECIFIED;
}
