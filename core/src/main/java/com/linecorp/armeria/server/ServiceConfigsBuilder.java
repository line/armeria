/*
 * Copyright 2023 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

interface ServiceConfigsBuilder<SELF extends ServiceConfigsBuilder<SELF>> {

    AbstractServiceBindingBuilder<?> route();

    AbstractBindingBuilder<?> routeDecorator();

    SELF serviceUnder(String pathPrefix, HttpService service);

    SELF service(String pathPattern, HttpService service);

    SELF service(Route route, HttpService service);

    SELF service(HttpServiceWithRoutes serviceWithRoutes,
                 Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators);

    SELF service(HttpServiceWithRoutes serviceWithRoutes,
                 Function<? super HttpService, ? extends HttpService>... decorators);

    SELF annotatedService(Object service);

    SELF annotatedService(Object service, Object... exceptionHandlersAndConverters);

    SELF annotatedService(Object service,
                          Function<? super HttpService, ? extends HttpService> decorator,
                          Object... exceptionHandlersAndConverters);

    SELF annotatedService(String pathPrefix, Object service);

    SELF annotatedService(String pathPrefix, Object service,
                          Object... exceptionHandlersAndConverters);

    SELF annotatedService(String pathPrefix, Object service,
                          Function<? super HttpService, ? extends HttpService> decorator,
                          Object... exceptionHandlersAndConverters);

    SELF annotatedService(String pathPrefix, Object service,
                          Iterable<?> exceptionHandlersAndConverters);

    SELF annotatedService(String pathPrefix, Object service,
                          Function<? super HttpService, ? extends HttpService> decorator,
                          Iterable<?> exceptionHandlersAndConverters);

    SELF annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    AnnotatedServiceConfigSetters<?> annotatedService();

    SELF decorator(Function<? super HttpService, ? extends HttpService> decorator);

    SELF decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    SELF decorator(String pathPattern, Function<? super HttpService, ? extends HttpService> decorator);

    SELF decorator(String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    SELF decorator(Route route, Function<? super HttpService, ? extends HttpService> decorator);

    SELF decorator(Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    SELF decoratorUnder(String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    SELF decoratorUnder(String prefix, Function<? super HttpService, ? extends HttpService> decorator);
}
