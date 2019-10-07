/*
 * Copyright 2019 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

public class AnnotatedServiceBindingBuilder extends AbstractServiceBuilder {

    private final ServerBuilder serverBuilder;
    private String pathPrefix = "/";
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();

    public AnnotatedServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    @Override
    public void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serverBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }

    public AnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    public AnnotatedServiceBindingBuilder exceptionHandler(ExceptionHandlerFunction exceptionHandlerFunction) {
        requireNonNull(exceptionHandlerFunction, "exceptionHandler");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunction);
        return this;
    }

    public AnnotatedServiceBindingBuilder responseHandler(ResponseConverterFunction responseConverterFunction) {
        requireNonNull(responseConverterFunction, "responseConverterFunction");
        responseConverterFunctionBuilder.add(responseConverterFunction);
        return this;
    }

    public AnnotatedServiceBindingBuilder requestHandler(RequestConverterFunction requestConverterFunction) {
        requireNonNull(requestConverterFunction, "requestConverterFunction");
        requestConverterFunctionBuilder.add(requestConverterFunction);
        return this;
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    AnnotatedServiceBindingBuilder decorator(Function<T, R> decorator) {
        return (AnnotatedServiceBindingBuilder) super.decorator(decorator);
    }

    public ServerBuilder build(Object service) {
        final ImmutableList<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlerFunctionBuilder.build();
        final ImmutableList<RequestConverterFunction> requestConverterFunctions =
                requestConverterFunctionBuilder.build();
        final ImmutableList<ResponseConverterFunction> responseConverterFunctions =
                responseConverterFunctionBuilder.build();

        final Iterable<?> exceptionHandlersAndConverters = ImmutableList.of(exceptionHandlerFunctions,
                                                                            requestConverterFunctions,
                                                                            responseConverterFunctions);
        final List<AnnotatedHttpServiceElement> elements =
            AnnotatedHttpServiceFactory.find(pathPrefix, service, exceptionHandlersAndConverters);
        elements.forEach(e -> {
            Service<HttpRequest, HttpResponse> s = e.service();
            // Apply decorators which are specified in the service class.
            s = e.decorator().apply(s);
            // Apply decorators which are passed via annotatedService() methods.
            //s = decorate(s); Moved to AbstractServiceBuilder

            // If there is a decorator, we should add one more decorator which handles an exception
            // raised from decorators.
            if (s != e.service()) {
                s = e.service().exceptionHandlingDecorator().apply(s);
            }
            build0(e.route(), s);
        });
        return serverBuilder;
    }
}
