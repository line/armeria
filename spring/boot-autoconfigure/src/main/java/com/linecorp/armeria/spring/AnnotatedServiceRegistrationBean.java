/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.spring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;

import javax.validation.constraints.NotNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A bean with information for registering an annotated service object. It enables dropwizard
 * monitoring of the service automatically.
 * <pre>{@code
 * > @Bean
 * > public AnnotatedServiceRegistrationBean okService() {
 * >     return new AnnotatedServiceRegistrationBean()
 * >             .setServiceName("myAnnotatedService")
 * >             .setPathPrefix("/my_service")
 * >             .setService(new MyAnnotatedService())
 * >             .setDecorator(LoggingService.newDecorator())
 * >             .setExceptionHandlers(new MyExceptionHandler())
 * >             .setRequestConverters(new MyRequestConverter())
 * >             .setResponseConverters(new MyResponseConverter());
 * > }
 * }</pre>
 */
public class AnnotatedServiceRegistrationBean {

    /**
     * The annotated service object to register.
     */
    @NotNull
    private Object service;

    /**
     * The path prefix of the annotated service object.
     */
    @NotNull
    private String pathPrefix = "/";

    /**
     * A service name to use in monitoring.
     */
    @NotNull
    private String serviceName;

    /**
     * The decorator of the annotated service object.
     */
    @NotNull
    private Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator = Function.identity();

    /**
     * The exception handlers of the annotated service object.
     */
    @NotNull
    private Collection<? extends ExceptionHandlerFunction> exceptionHandlers = new ArrayList<>();

    /**
     * The request converters of the annotated service object.
     */
    @NotNull
    private Collection<? extends RequestConverterFunction> requestConverters = new ArrayList<>();

    /**
     * The response converters of the annotated service object.
     */
    @NotNull
    private Collection<? extends ResponseConverterFunction> responseConverters = new ArrayList<>();

    /**
     * Returns the annotated service object registered to this bean.
     */
    @NotNull
    public Object getService() {
        return service;
    }

    /**
     * Registers an annotated service object.
     */
    public AnnotatedServiceRegistrationBean setService(@NotNull Object service) {
        this.service = service;
        return this;
    }

    /**
     * Returns the path prefix.
     */
    @NotNull
    public String getPathPrefix() {
        return pathPrefix;
    }

    /**
     * Sets the path prefix.
     */
    public AnnotatedServiceRegistrationBean setPathPrefix(@NotNull String pathPrefix) {
        this.pathPrefix = pathPrefix;
        return this;
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
    public AnnotatedServiceRegistrationBean setServiceName(@NotNull String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    /**
     * Returns the decorator of the annotated service object.
     */
    @NotNull
    public Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> getDecorator() {
        return decorator;
    }

    /**
     * Sets the decorator of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setDecorator(
            @NotNull Function<Service<HttpRequest, HttpResponse>,
                              ? extends Service<HttpRequest, HttpResponse>> decorator) {
        this.decorator = decorator;
        return this;
    }

    /**
     * Returns the exception handlers of the annotated service object.
     */
    public Collection<? extends ExceptionHandlerFunction> getExceptionHandlers() {
        return exceptionHandlers;
    }

    /**
     * Sets the exception handlers of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setExceptionHandlers(
            Collection<? extends ExceptionHandlerFunction> exceptionHandlers) {
        this.exceptionHandlers = exceptionHandlers;
        return this;
    }

    /**
     * Sets the exception handlers of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setExceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlers) {
        return setExceptionHandlers(ImmutableList.copyOf(exceptionHandlers));
    }

    /**
     * Returns the request converters of the annotated service object.
     */
    public Collection<? extends RequestConverterFunction> getRequestConverters() {
        return requestConverters;
    }

    /**
     * Sets the request converters of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setRequestConverters(
            Collection<? extends RequestConverterFunction> requestConverters) {
        this.requestConverters = requestConverters;
        return this;
    }

    /**
     * Sets the request converters of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setRequestConverters(
            RequestConverterFunction... requestConverters) {
        return setRequestConverters(ImmutableList.copyOf(requestConverters));
    }

    /**
     * Returns the response converters of the annotated service object.
     */
    public Collection<? extends ResponseConverterFunction> getResponseConverters() {
        return responseConverters;
    }

    /**
     * Sets the response converters of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setResponseConverters(
            Collection<? extends ResponseConverterFunction> responseConverters) {
        this.responseConverters = responseConverters;
        return this;
    }

    /**
     * Sets the response converters of the annotated service object.
     */
    public AnnotatedServiceRegistrationBean setResponseConverters(
            ResponseConverterFunction... responseConverters) {
        return setResponseConverters(ImmutableList.copyOf(responseConverters));
    }
}
