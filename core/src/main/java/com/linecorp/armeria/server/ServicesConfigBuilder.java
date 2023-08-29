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

interface ServicesConfigBuilder {

    AbstractServiceBindingBuilder route();

    AbstractBindingBuilder routeDecorator();

    ServicesConfigBuilder serviceUnder(String pathPrefix, HttpService service);

    ServicesConfigBuilder service(String pathPattern, HttpService service);

    ServicesConfigBuilder service(Route route, HttpService service);

    ServicesConfigBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators);

    ServicesConfigBuilder service(HttpServiceWithRoutes serviceWithRoutes,
                                  Function<? super HttpService, ? extends HttpService>... decorators);

    ServicesConfigBuilder annotatedService(Object service);

    ServicesConfigBuilder annotatedService(Object service,
                                           Object... exceptionHandlersAndConverters);

    ServicesConfigBuilder annotatedService(Object service,
                                           Function<? super HttpService, ? extends HttpService> decorator,
                                           Object... exceptionHandlersAndConverters);

    ServicesConfigBuilder annotatedService(String pathPrefix, Object service);

    ServicesConfigBuilder annotatedService(String pathPrefix, Object service,
                                           Object... exceptionHandlersAndConverters);

    ServicesConfigBuilder annotatedService(String pathPrefix, Object service,
                                           Function<? super HttpService, ? extends HttpService> decorator,
                                           Object... exceptionHandlersAndConverters);

    ServicesConfigBuilder annotatedService(String pathPrefix, Object service,
                                           Iterable<?> exceptionHandlersAndConverters);

    ServicesConfigBuilder annotatedService(String pathPrefix, Object service,
                                           Function<? super HttpService, ? extends HttpService> decorator,
                                           Iterable<?> exceptionHandlersAndConverters);

    ServicesConfigBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    AnnotatedServiceConfigSetters annotatedService();

    ServicesConfigBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator);

    ServicesConfigBuilder decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServicesConfigBuilder decorator(String pathPattern,
                                    Function<? super HttpService, ? extends HttpService> decorator);

    ServicesConfigBuilder decorator(String pathPattern,
                                    DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServicesConfigBuilder decorator(Route route,
                                    Function<? super HttpService, ? extends HttpService> decorator);

    ServicesConfigBuilder decorator(Route route,
                                    DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServicesConfigBuilder decoratorUnder(String prefix,
                                         DecoratingHttpServiceFunction decoratingHttpServiceFunction);

    ServicesConfigBuilder decoratorUnder(String prefix,
                                         Function<? super HttpService, ? extends HttpService> decorator);
}
