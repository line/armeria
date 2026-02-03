/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.server.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.annotation.DefaultValues;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.Markup;

/**
 * An annotation used to describe the return value of an annotated HTTP service method.
 * This provides an alternative to Javadoc {@code @return} tags and takes precedence
 * when both exist.
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Get("/users/{id}")
 * @ReturnDescription("The user with the specified ID")
 * public User getUser(@Param String id) {
 *     ...
 * }
 * }</pre>
 */
@Documented
@UnstableApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReturnDescription {

    /**
     * The description of the return value.
     */
    String value() default DefaultValues.UNSPECIFIED;

    /**
     * The supported markup type in {@link DocService}.
     */
    Markup markup() default Markup.NONE;
}
