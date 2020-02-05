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

import static com.linecorp.armeria.internal.server.annotation.DefaultValues.UNSPECIFIED;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the default value of an optional parameter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR })
public @interface Default {

    /**
     * The default value to use as a fallback when the request parameter is not provided or has an empty value.
     * When {@link Default} annotation exists but {@link Default#value()} is not specified, {@code null}
     * value would be set if the parameter is not present in the request.
     *
     * {@link Default} annotation is not allowed for a path variable. If a user uses {@link Default}
     * annotation on a path variable, {@link IllegalArgumentException} would be raised.
     */
    String value() default UNSPECIFIED;
}
