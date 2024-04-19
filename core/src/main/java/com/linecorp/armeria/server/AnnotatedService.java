/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.server;

import java.lang.reflect.Method;

import com.linecorp.armeria.common.HttpStatus;

/**
 * AnnotatedService has specific value on their private fields. User may want to access
 * them. This interface is to expose specific values of AnnotatedService to public.
 */
public interface AnnotatedService extends HttpService {

    /**
     * Should return Object of AnnotationService.
     */
    Object object();

    /**
     * Should return {@link Method} of AnnotationService.
     */
    Method method();

    /**
     * Return AnnotatedService's method.
     */
    int overloadId();

    /**
     * Should return {@link Route} of AnnotationService.
     */
    Route route();

    /**
     * Should return DefaultStatus code of AnnotationService.
     */
    HttpStatus defaultStatus();
}
