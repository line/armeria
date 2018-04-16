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

import static com.linecorp.armeria.internal.DefaultValues.UNSPECIFIED;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.annotation.AliasFor;

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
     * Alias for {@link #name}.
     * <p>Intended to be used instead of {@link #name} when {@link #defaultValue}
     * is not declared &mdash; for example: {@code @Param("userName")} instead of
     * {@code @Param(name = "userName")}.
     */
    @AliasFor("name")
    String value() default UNSPECIFIED;

    /**
     * The name of the request parameter to bind to.
     * The path variable, the parameter name in a query string or a URL-encoded form data,
     * or the name of a multipart.
     * @see #value
     */
    @AliasFor("value")
    String name() default UNSPECIFIED;

    /**
     * The default value to use as a fallback when the request parameter is not provided or has an empty value.
     * When {@link #defaultValue} is not specified, {@code null}
     * value would be set if the parameter is not present in the request.
     *
     * {@link #defaultValue} is not allowed for a path variable. If a user uses {@link #defaultValue} on a path
     * variable, {@link IllegalArgumentException} would be raised.
     */
    String defaultValue() default UNSPECIFIED;
}
