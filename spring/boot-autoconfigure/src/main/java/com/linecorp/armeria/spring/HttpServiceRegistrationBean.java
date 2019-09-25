/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.spring;

import java.util.Collection;

import javax.validation.constraints.NotNull;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;

/**
 * A bean with information for registering a http service.
 * It enables Micrometer metric collection of the service automatically.
 * <pre>{@code
 * > @Bean
 * > public HttpServiceRegistrationBean okService() {
 * >     return new HttpServiceRegistrationBean()
 * >             .setServiceName("okService")
 * >             .setService(new OkService())
 * >             .setRoute(Route.builder().path("/ok").methods(HttpMethod.GET, HttpMethod.POST).build())
 * >             .setDecorators(LoggingService.newDecorator());
 * > }
 * }</pre>
 */
public class HttpServiceRegistrationBean
        extends
        AbstractServiceRegistrationBean<Service<HttpRequest, HttpResponse>, HttpServiceRegistrationBean,
                Object, HttpHeaders> {

    /**
     * The {@link Route} for the http service.
     */
    @NotNull
    private Route route;

    /**
     * Returns the {@link Route} that this service map to.
     */
    @NotNull
    public Route getRoute() {
        return route;
    }

    /**
     * Sets a {@link Route} that this service map to.
     */
    public HttpServiceRegistrationBean setRoute(@NotNull Route route) {
        this.route = route;
        return this;
    }

    /**
     * Sets the path pattern of the service.
     */
    public HttpServiceRegistrationBean setPathPattern(@NotNull String pathPattern) {
        return setRoute(Route.builder().path(pathPattern).build());
    }

    @Override
    public HttpServiceRegistrationBean setExampleRequests(@NotNull Collection<Object> exampleRequests) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean setExampleRequests(@NotNull Iterable<Object> exampleRequests) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean setExampleRequests(Object... exampleRequests) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleRequests(@NotNull Collection<Object> exampleRequests) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleRequests(@NotNull Iterable<Object> exampleRequests) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleRequests(Object... exampleRequests) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleRequest(@NotNull Object exampleRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean setExampleHeaders(@NotNull Collection<HttpHeaders> exampleHeaders) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean setExampleHeaders(@NotNull Iterable<HttpHeaders> exampleHeaders) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean setExampleHeaders(@NotNull HttpHeaders... exampleHeaders) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleHeaders(@NotNull Collection<HttpHeaders> exampleHeaders) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleHeaders(@NotNull Iterable<HttpHeaders> exampleHeaders) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleHeaders(HttpHeaders... exampleHeaders) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServiceRegistrationBean addExampleHeader(HttpHeaders exampleHeaders) {
        throw new UnsupportedOperationException();
    }
}
