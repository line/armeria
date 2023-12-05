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

interface ServiceConfigsBuilder {

    AbstractServiceBindingBuilder route();

    AbstractBindingBuilder routeDecorator();

    ServiceConfigsBuilder serviceUnder(String pathPrefix, HttpService service);

    ServiceConfigsBuilder service(String pathPattern, HttpService service);

    ServiceConfigsBuilder service(Route route, HttpService service);

    ServiceConfigsBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators);

    ServiceConfigsBuilder service(HttpServiceWithRoutes serviceWithRoutes,
                                  Function<? super HttpService, ? extends HttpService>... decorators);

    ServiceConfigsBuilder annotatedService(Object service);

    ServiceConfigsBuilder annotatedService(Object service,
                                           Object... exceptionHandlersAndConverters);

    ServiceConfigsBuilder annotatedService(Object service,
                                           Function<? super HttpService, ? extends HttpService> decorator,
                                           Object... exceptionHandlersAndConverters);

    ServiceConfigsBuilder annotatedService(String pathPrefix, Object service);

    ServiceConfigsBuilder annotatedService(String pathPrefix, Object service,
                                           Object... exceptionHandlersAndConverters);

    ServiceConfigsBuilder annotatedService(String pathPrefix, Object service,
                                           Function<? super HttpService, ? extends HttpService> decorator,
                                           Object... exceptionHandlersAndConverters);

    ServiceConfigsBuilder annotatedService(String pathPrefix, Object service,
                                           Iterable<?> exceptionHandlersAndConverters);

    ServiceConfigsBuilder annotatedService(String pathPrefix, Object service,
                                           Function<? super HttpService, ? extends HttpService> decorator,
                                           Iterable<?> exceptionHandlersAndConverters);

    ServiceConfigsBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    AnnotatedServiceConfigSetters annotatedService();

    ServiceConfigsBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator);

    ServiceConfigsBuilder decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServiceConfigsBuilder decorator(String pathPattern,
                                    Function<? super HttpService, ? extends HttpService> decorator);

    ServiceConfigsBuilder decorator(String pathPattern,
                                    DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServiceConfigsBuilder decorator(Route route,
                                    Function<? super HttpService, ? extends HttpService> decorator);

    ServiceConfigsBuilder decorator(Route route,
                                    DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServiceConfigsBuilder decoratorUnder(String prefix,
                                         DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServiceConfigsBuilder decoratorUnder(String prefix,
                                         Function<? super HttpService, ? extends HttpService> decorator);
}
