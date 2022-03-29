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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.internal.server.annotation.DefaultValues;

/**
 * Specifies a delimiter of a parameter.
 *
 * <p>The {@link Delimiter} annotation has precedence over annotated service settings.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR })
public @interface Delimiter {

    /**
     * A delimiter to use when the request parameter is resolved to collection type and the number of values of
     * the request parameter is one. When {@link Delimiter} annotation exists but {@link Delimiter#value()} is
     * not specified, the parameter would not be delimited.
     *
     * <p>Note that the {@link Delimiter} annotation is only allowed for a query parameter.</p>
     */
    String value() default DefaultValues.UNSPECIFIED;
}
