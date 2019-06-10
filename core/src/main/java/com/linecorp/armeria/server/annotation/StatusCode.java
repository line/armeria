/*
 * Copyright 2019 LINE Corporation
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

/**
 * An annotation which specifies a default HTTP status code of a response produced by an annotated HTTP service.
 * If this annotation is missing, {@code @StatusCode(200)} or {@code @StatusCode(204)} would be applied
 * according to the return type of the annotated method. If the return type is {@code void} or {@link Void},
 * {@code @StatusCode(204)} would be applied. Otherwise, {@code @StatusCode(200)} would be applied.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
public @interface StatusCode {
    /**
     * A default HTTP status code of a response produced by an annotated HTTP service.
     */
    int value();
}
