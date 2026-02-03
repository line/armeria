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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.annotation.DefaultValues;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.Markup;

/**
 * An annotation used to describe exceptions thrown by an annotated HTTP service method.
 * This provides an alternative to Javadoc {@code @throws} tags and takes precedence
 * when both exist.
 *
 * <p>This annotation is repeatable, allowing multiple exceptions to be documented:</p>
 * <pre>{@code
 * @Get("/users/{id}")
 * @ThrowsDescription(value = IllegalArgumentException.class, description = "If the ID is invalid")
 * @ThrowsDescription(value = NotFoundException.class, description = "If the user is not found")
 * public User getUser(@Param String id) {
 *     ...
 * }
 * }</pre>
 */
@Documented
@UnstableApi
@Repeatable(ThrowsDescriptions.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ThrowsDescription {

    /**
     * The exception type that may be thrown.
     */
    Class<? extends Throwable> value();

    /**
     * The description of when or why the exception is thrown.
     */
    String description() default DefaultValues.UNSPECIFIED;

    /**
     * The supported markup type in {@link DocService}.
     */
    Markup markup() default Markup.NONE;
}
