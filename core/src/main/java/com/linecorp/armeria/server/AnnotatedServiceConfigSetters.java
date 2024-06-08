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

package com.linecorp.armeria.server;

import java.util.concurrent.Executors;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

@UnstableApi
interface AnnotatedServiceConfigSetters<SELF extends AnnotatedServiceConfigSetters<SELF>>
        extends ServiceConfigSetters<SELF> {

    /**
     * Sets the path prefix to be used for this {@link AnnotatedServiceConfigSetters}.
     * @param pathPrefix string representing the path prefix.
     */
    SELF pathPrefix(String pathPrefix);

    /**
     * Adds the given {@link ExceptionHandlerFunction}s to this {@link AnnotatedServiceConfigSetters}.
     */
    SELF exceptionHandlers(ExceptionHandlerFunction... exceptionHandlerFunctions);

    /**
     * Adds the given {@link ExceptionHandlerFunction}s to this {@link AnnotatedServiceConfigSetters}.
     */
    SELF exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions);

    /**
     * Adds the given {@link ResponseConverterFunction}s to this {@link AnnotatedServiceConfigSetters}.
     */
    SELF responseConverters(ResponseConverterFunction... responseConverterFunctions);

    /**
     * Adds the given {@link ResponseConverterFunction}s to this {@link AnnotatedServiceConfigSetters}.
     */
    SELF responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    /**
     * Adds the given {@link RequestConverterFunction}s to this {@link AnnotatedServiceConfigSetters}.
     */
    SELF requestConverters(RequestConverterFunction... requestConverterFunctions);

    /**
     * Adds the given {@link RequestConverterFunction}s to this {@link AnnotatedServiceConfigSetters}.
     */
    SELF requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions);

    /**
     * Sets whether the service executes service methods using the blocking executor. By default, service
     * methods are executed directly on the event loop for implementing fully asynchronous services. If your
     * service uses blocking logic, you should either execute such logic in a separate thread using something
     * like {@link Executors#newCachedThreadPool()} or enable this setting.
     */
    SELF useBlockingTaskExecutor(boolean useBlockingTaskExecutor);
}
