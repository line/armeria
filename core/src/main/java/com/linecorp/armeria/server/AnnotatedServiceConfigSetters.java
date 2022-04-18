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

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

interface AnnotatedServiceConfigSetters extends ServiceConfigSetters {

    AnnotatedServiceConfigSetters pathPrefix(String pathPrefix);

    AnnotatedServiceConfigSetters exceptionHandlers(ExceptionHandlerFunction... exceptionHandlerFunctions);

    AnnotatedServiceConfigSetters exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions);

    AnnotatedServiceConfigSetters responseConverters(ResponseConverterFunction... responseConverterFunctions);

    AnnotatedServiceConfigSetters responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    AnnotatedServiceConfigSetters requestConverters(RequestConverterFunction... requestConverterFunctions);

    AnnotatedServiceConfigSetters requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions);

    AnnotatedServiceConfigSetters useBlockingTaskExecutor(boolean useBlockingTaskExecutor);
}
