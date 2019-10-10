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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding a {@link Service} fluently. This class can be instantiated through
 * {@link ServerBuilder#annotatedService()}.
 *
 * <p>Call {@link #buildAnnotated(Object)} to build the {@link Service} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.annotatedService()                                    // Returns an instance of this class
 *   .requestTimeout(5000)
 *   .maxRequestLength(8192)
 *   .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
 *   .pathPrefix("/foo")
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .buildAnnotated(new Service())                         // Return to the ServerBuilder.
 *   .build();
 * }</pre>
 *
 * @see ServiceBindingBuilder
 */
public class AnnotatedServiceBindingBuilder extends AbstractServiceBuilder {

    private final ServerBuilder serverBuilder;
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();
    private String pathPrefix = "/";

    public AnnotatedServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
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

    @Override
    public AnnotatedServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return (AnnotatedServiceBindingBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return (AnnotatedServiceBindingBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public AnnotatedServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        return (AnnotatedServiceBindingBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public AnnotatedServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        return (AnnotatedServiceBindingBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (AnnotatedServiceBindingBuilder) super.requestContentPreviewerFactory(factory);
    }

    @Override
    public AnnotatedServiceBindingBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (AnnotatedServiceBindingBuilder) super.responseContentPreviewerFactory(factory);
    }

    @Override
    public AnnotatedServiceBindingBuilder contentPreview(int length) {
        return (AnnotatedServiceBindingBuilder) super.contentPreview(length);
    }

    @Override
    public AnnotatedServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        return (AnnotatedServiceBindingBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public AnnotatedServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (AnnotatedServiceBindingBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public AnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return (AnnotatedServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public AnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                          boolean shutdownOnStop) {
        return (AnnotatedServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    public ServerBuilder buildAnnotated(Object service) {
        final ImmutableList<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlerFunctionBuilder.build();
        final ImmutableList<RequestConverterFunction> requestConverterFunctions =
                requestConverterFunctionBuilder.build();
        final ImmutableList<ResponseConverterFunction> responseConverterFunctions =
                responseConverterFunctionBuilder.build();

        final Iterable<?> exceptionHandlersAndConverters = Iterables.concat(exceptionHandlerFunctions,
                                                                            requestConverterFunctions,
                                                                            responseConverterFunctions);
        final List<AnnotatedHttpServiceElement> elements =
                AnnotatedHttpServiceFactory.find(pathPrefix, service, exceptionHandlersAndConverters);
        elements.forEach(e -> {
            Service<HttpRequest, HttpResponse> s = e.service();
            // Apply decorators which are specified in the service class.
            s = e.decorator().apply(s);
            // Apply decorator passed in through this builder
            s = decorate(s);
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
