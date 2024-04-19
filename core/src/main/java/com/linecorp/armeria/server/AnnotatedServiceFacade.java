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
package com.linecorp.armeria.server;

import java.lang.reflect.Method;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.StatusCode;

/**
 * An {@link HttpService} which is defined by a {@link Path} or HTTP method annotations.
 * {@link AnnotatedServiceFacade} is intended for public access.
 * Please check out the documentation at
 * <a href="https://armeria.dev/docs/server-annotated-service">Annotated HTTP Service</a> to use this.
 */
public interface AnnotatedServiceFacade extends HttpService {

    /**
     * Returns the annotated service object specified with {@link ServerBuilder#annotatedService(Object)}.
     */
    Object object();

    /**
     * Returns the target {@link Method} invoked when the request is received.
     */
    Method method();

    /**
     * Returns {@link Route} for this {@link AnnotatedService}.
     */
    Route route();

    /**
     * Returns the default {@link HttpStatus} specified with {@link StatusCode}.
     * If {@link StatusCode} is not given, {@link HttpStatus#OK} is returned by default.
     * If the method returns a void type such as {@link Void} or Kotlin Unit, {@link HttpStatus#NO_CONTENT} is
     * returned.
     */
    HttpStatus defaultStatus();

    /**
     * Returns an {@link HttpService} decorated with an {@link ExceptionHandler}.
     */
    HttpService withExceptionHandler(HttpService service);
}
