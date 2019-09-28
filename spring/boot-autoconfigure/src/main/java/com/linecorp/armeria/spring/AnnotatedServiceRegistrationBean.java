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

import javax.validation.constraints.NotNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A bean with information for registering an annotated service object.
 * It enables Micrometer metric collection of the service automatically.
 * <pre>{@code
 * > @Bean
 * > public AnnotatedServiceRegistrationBean okService() {
 * >     return new AnnotatedServiceRegistrationBean()
 * >             .setServiceName("myAnnotatedService")
 * >             .setPathPrefix("/my_service")
 * >             .setService(new MyAnnotatedService())
 * >             .setDecorators(LoggingService.newDecorator())
 * >             .setExceptionHandlers(new MyExceptionHandler())
 * >             .setRequestConverters(new MyRequestConverter())
 * >             .setResponseConverters(new MyResponseConverter())
 * >             .addExampleRequest(AnnotatedExampleRequest.of("myMethod", "{\"foo\":\"bar\"}"))
 * >             .addExampleHeader(ExampleHeader.of("my-header", "headerVal"));
 * > }
 * }</pre>
 */
public class AnnotatedServiceRegistrationBean
        extends AbstractServiceRegistrationBean<Object, AnnotatedServiceRegistrationBean,
        AnnotatedExampleRequest, ExampleHeader> {

    /**
     * The path prefix of the annotated service object.
     */
    @NotNull
    private String pathPrefix = "/";

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

    /**
     * Adds an example request for {@link #getService()}.
     */
    public AnnotatedServiceRegistrationBean addExampleRequest(@NotNull String methodName,
                                                              @NotNull Object exampleRequest) {
        return addExampleRequest(AnnotatedExampleRequest.of(methodName, exampleRequest));
    }

    /**
     * Adds an example HTTP header.
     */
    public AnnotatedServiceRegistrationBean addExampleHeader(CharSequence name, String value) {
        return addExampleHeader(ExampleHeader.of(name, value));
    }

    /**
     * Adds an example HTTP header for the method.
     */
    public AnnotatedServiceRegistrationBean addExampleHeader(String methodName, HttpHeaders exampleHeaders) {
        return addExampleHeader(ExampleHeader.of(methodName, exampleHeaders));
    }

    /**
     * Adds an example HTTP header for the method.
     */
    public AnnotatedServiceRegistrationBean addExampleHeader(String methodName, CharSequence name,
                                                             String value) {
        return addExampleHeader(ExampleHeader.of(methodName, name, value));
    }

    /**
     * Adds example HTTP headers for the method.
     */
    public AnnotatedServiceRegistrationBean addExampleHeader(String methodName,
                                                             @NotNull Collection<HttpHeaders> exampleHeaders) {
        exampleHeaders.forEach(h -> addExampleHeader(methodName, h));
        return this;
    }

    /**
     * Adds example HTTP headers for the method.
     */
    public AnnotatedServiceRegistrationBean addExampleHeader(String methodName,
                                                             @NotNull Iterable<HttpHeaders> exampleHeaders) {
        return addExampleHeader(methodName, ImmutableList.copyOf(exampleHeaders));
    }

    /**
     * Adds example HTTP headers for the method.
     */
    public AnnotatedServiceRegistrationBean addExampleHeader(String methodName,
                                                             @NotNull HttpHeaders... exampleHeaders) {
        return addExampleHeader(methodName, ImmutableList.copyOf(exampleHeaders));
    }
}
