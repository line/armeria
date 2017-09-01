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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for specifying {@link ResponseConverter} onto a class or a method.
 *
 * <p>This annotation has following restrictions:
 * <ul>
 * <li>When marked on a class, it can be repeated and must specify the target class, except
 * {@link Object}.class.</li>
 * <li>When marked on a method, it can't be repeated and should not specify the target class.</li>
 * </ul>
 */
@Repeatable(Converters.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Converter {
    /**
     * Target type to be converted.
     */
    Class<?> target() default Unspecified.class;

    /**
     * The type of the {@link ResponseConverter} that will convert the object whose type is {@link #target()}.
     */
    Class<? extends ResponseConverter> value();

    /**
     * Indicates that {@link #target()} is not specified by a user.
     */
    final class Unspecified {
        private Unspecified() {}
    }
}
