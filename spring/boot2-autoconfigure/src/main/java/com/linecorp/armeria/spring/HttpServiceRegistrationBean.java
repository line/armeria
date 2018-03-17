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

import java.util.function.Function;

import javax.validation.constraints.NotNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;

/**
 * A bean with information for registering a http service. It enables dropwizard
 * monitoring of the service automatically.
 * <pre>{@code
 * > @Bean
 * > public HttpServiceRegistrationBean okService() {
 * >     return new HttpServiceRegistrationBean()
 * >             .setServiceName("okService")
 * >             .setService(new OkService())
 * >             .setPathMapping(PathMapping.ofExact("/ok"))
 * >             .setDecorator(LoggingService.newDecorator());
 * > }
 * }</pre>
 */
public class HttpServiceRegistrationBean {

    /**
     * The http service to register.
     */
    @NotNull
    private Service<HttpRequest, HttpResponse> service;

    /**
     * The pathMapping for the http service. For example, {@code PathMapping.ofPrefix("/foobar")}.
     */
    @NotNull
    private PathMapping pathMapping;

    /**
     * A service name to use in monitoring.
     */
    @NotNull
    private String serviceName;

    /**
     * The decorator of the HTTP service.
     */
    @NotNull
    private Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator = Function.identity();

    /**
     * Returns the http {@link Service} registered to this bean.
     */
    @NotNull
    public Service<HttpRequest, HttpResponse> getService() {
        return service;
    }

    /**
     * Register a http {@link Service}.
     */
    public HttpServiceRegistrationBean setService(@NotNull Service<HttpRequest, HttpResponse> service) {
        this.service = service;
        return this;
    }

    /**
     * Returns the {@link PathMapping} that this service map to.
     */
    @NotNull
    public PathMapping getPathMapping() {
        return pathMapping;
    }

    /**
     * Sets a {@link PathMapping} that this service map to.
     */
    public HttpServiceRegistrationBean setPathMapping(@NotNull PathMapping pathMapping) {
        this.pathMapping = pathMapping;
        return this;
    }

    /**
     * Sets the path pattern of the service.
     */
    public HttpServiceRegistrationBean setPathPattern(@NotNull String pathPattern) {
        return setPathMapping(PathMapping.of(pathPattern));
    }

    /**
     * Returns this service name to use in monitoring.
     */
    @NotNull
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Sets service name to use in monitoring.
     */
    public HttpServiceRegistrationBean setServiceName(@NotNull String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    /**
     * Returns the decorator of the HTTP service.
     */
    @NotNull
    public Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> getDecorator() {
        return decorator;
    }

    /**
     * Sets the decorator of the HTTP service.
     */
    public HttpServiceRegistrationBean setDecorator(
            @NotNull Function<Service<HttpRequest, HttpResponse>,
                              ? extends Service<HttpRequest, HttpResponse>> decorator) {
        this.decorator = decorator;
        return this;
    }
}
