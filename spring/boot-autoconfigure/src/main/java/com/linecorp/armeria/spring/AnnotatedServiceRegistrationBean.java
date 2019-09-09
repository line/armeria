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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;

import javax.validation.constraints.NotNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.docs.DocService;

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
 * >             .setResponseConverters(new MyResponseConverter());
 * > }
 * }</pre>
 */
public class AnnotatedServiceRegistrationBean
        extends AbstractServiceRegistrationBean<Object, AnnotatedServiceRegistrationBean> {

    public static final class AnnotatedServiceExampleRequest {
        private final String methodName;
        private final Object exampleRequest;

        private AnnotatedServiceExampleRequest(String methodName, Object exampleRequest) {
            this.methodName = methodName;
            this.exampleRequest = exampleRequest;
        }

        public String getMethodName() {
            return methodName;
        }

        public Object getExampleRequest() {
            return exampleRequest;
        }

        public static AnnotatedServiceExampleRequest of(@NotNull String methodName,
                                                        @NotNull Object exampleRequest) {
            return new AnnotatedServiceExampleRequest(methodName, exampleRequest);
        }
    }

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
     * Sample requests to populate debug forms in {@link DocService}.
     * This should be a list of request objects which correspond to methods
     * in this annotated service.
     */
    @NotNull
    private final Collection<AnnotatedServiceExampleRequest> exampleRequests = new ArrayList<>();

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
     * Returns sample requests of {@link #getService()}.
     */
    @NotNull
    public Collection<AnnotatedServiceExampleRequest> getExampleRequests() {
        return exampleRequests;
    }

    /**
     * Sets sample requests for {@link #getService()}.
     */
    public AnnotatedServiceRegistrationBean addExampleRequest(String methodName, Object exampleRequest) {
        requireNonNull(methodName, "methodName");
        checkArgument(!methodName.isEmpty(), "methodName is empty.");
        requireNonNull(exampleRequest, "exampleRequest");
        exampleRequests.add(AnnotatedServiceExampleRequest.of(methodName, exampleRequest));
        return this;
    }
}
