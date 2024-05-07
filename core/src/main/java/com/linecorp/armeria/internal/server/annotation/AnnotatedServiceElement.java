/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.annotation.AnnotatedService;

/**
 * Details of an annotated HTTP service method.
 */
public final class AnnotatedServiceElement {

    private final Route route;

    private final DefaultAnnotatedService service;

    private final Function<? super HttpService, ? extends HttpService> decorator;

    AnnotatedServiceElement(Route route,
                            DefaultAnnotatedService service,
                            Function<? super HttpService, ? extends HttpService> decorator) {
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
        this.decorator = requireNonNull(decorator, "decorator");
    }

    /**
     * Returns the {@link Route}.
     */
    public Route route() {
        return route;
    }

    /**
     * Returns the {@link AnnotatedService} that will handle the request.
     */
    public AnnotatedService service() {
        return service;
    }

    /**
     * Returns the decorator of the {@link AnnotatedService} which will be evaluated at service
     * registration time.
     */
    public Function<? super HttpService, ? extends HttpService> decorator() {
        return decorator;
    }

    /**
     * Builds a safe decorated {@link HttpService} by wrapping the localDecorator with
     * exceptionHandlingDecorators.
     *
     * @param localDecorator a decorator to decorate the service with.
     */
    public HttpService buildSafeDecoratedService(
            Function<? super HttpService, ? extends HttpService> localDecorator) {
        // Apply decorators which are specified in the service class.
        HttpService decoratedService = decorator.apply(service);
        // Apply localDecorator passed in through method parameter
        decoratedService = decoratedService.decorate(localDecorator);
        return service.withExceptionHandler(decoratedService);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("route", route())
                          .add("service", service())
                          .add("decorator", decorator())
                          .toString();
    }
}
