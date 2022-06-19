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

import com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin;
import com.linecorp.armeria.server.docs.DocService;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies which element should be converted to structs in {@link DocService} by {@link AnnotatedDocServicePlugin}.
 *
 * The following example shows when to apply a {@link ResponseObject} implicitly.
 * <pre>{@code
 * > @ResponseObject
 * > public class ResponseBean {
 * >     private String responseA;
 * >     private String responseB;
 * > }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface ResponseObject {}
