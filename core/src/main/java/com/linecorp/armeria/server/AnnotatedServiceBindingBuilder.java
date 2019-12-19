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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceExtensions;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link ServerBuilder#annotatedService()}.
 *
 * <p>Call {@link #build(Object)} to build the {@link HttpService} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.annotatedService()                       // Returns an instance of this class
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
 *   .pathPrefix("/foo")
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .build(new Service())                     // Return to the ServerBuilder.
 *   .build();
 * }</pre>
 *
 * @see ServiceBindingBuilder
 */
public final class AnnotatedServiceBindingBuilder implements ServiceConfigSetters {

    private final ServerBuilder serverBuilder;
    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();
    private String pathPrefix = "/";
    @Nullable
    private Object service;

    AnnotatedServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    /**
     * Sets the path prefix to be used for this {@link AnnotatedServiceBindingBuilder}.
     * @param pathPrefix string representing the path prefix.
     */
    public AnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction} to this {@link AnnotatedServiceBindingBuilder}.
     *
     * @deprecated Use {@link #exceptionHandlers(ExceptionHandlerFunction...)}.
     */
    @Deprecated
    public AnnotatedServiceBindingBuilder exceptionHandler(ExceptionHandlerFunction exceptionHandlerFunction) {
        requireNonNull(exceptionHandlerFunction, "exceptionHandlerFunction");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunction);
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction}s to this {@link AnnotatedServiceBindingBuilder}.
     */
    public AnnotatedServiceBindingBuilder exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunctions);
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction}s to this {@link AnnotatedServiceBindingBuilder}.
     */
    public AnnotatedServiceBindingBuilder exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(exceptionHandlerFunctions);
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction} to this {@link AnnotatedServiceBindingBuilder}.
     *
     * @deprecated Use {@link #responseConverters(ResponseConverterFunction...)}.
     */
    @Deprecated
    public AnnotatedServiceBindingBuilder responseConverter(
            ResponseConverterFunction responseConverterFunction) {
        requireNonNull(responseConverterFunction, "responseConverterFunction");
        responseConverterFunctionBuilder.add(responseConverterFunction);
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction}s to this {@link AnnotatedServiceBindingBuilder}.
     */
    public AnnotatedServiceBindingBuilder responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.add(responseConverterFunctions);
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction}s to this {@link AnnotatedServiceBindingBuilder}.
     */
    public AnnotatedServiceBindingBuilder responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(responseConverterFunctions);
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction} to this {@link AnnotatedServiceBindingBuilder}.
     *
     * @deprecated Use {@link #requestConverters(RequestConverterFunction...)}.
     */
    @Deprecated
    public AnnotatedServiceBindingBuilder requestConverter(RequestConverterFunction requestConverterFunction) {
        requireNonNull(requestConverterFunction, "requestConverterFunction");
        requestConverterFunctionBuilder.add(requestConverterFunction);
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction}s to this {@link AnnotatedServiceBindingBuilder}.
     */
    public AnnotatedServiceBindingBuilder requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.add(requestConverterFunctions);
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction}s to this {@link AnnotatedServiceBindingBuilder}.
     */
    public AnnotatedServiceBindingBuilder requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(requestConverterFunctions);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.requestContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder contentPreview(int length) {
        defaultServiceConfigSetters.contentPreview(length);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        defaultServiceConfigSetters.contentPreview(length, defaultCharset);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.contentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        defaultServiceConfigSetters.accessLogFormat(accessLogFormat);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                          boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    /**
     * Registers the given service to {@link ServerBuilder} and return {@link ServerBuilder}
     * to continue building {@link Server}.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     * @return {@link ServerBuilder} to continue building {@link Server}
     */
    public ServerBuilder build(Object service) {
        requireNonNull(service, "service");
        this.service = service;
        serverBuilder.annotatedServiceBindingBuilder(this);
        return serverBuilder;
    }

    /**
     * Builds the {@link ServiceConfigBuilder}s created with the configured
     * {@link AnnotatedHttpServiceExtensions} to the {@link ServerBuilder}.
     *
     * @param extensions the {@link AnnotatedHttpServiceExtensions} at the server level.
     */
    List<ServiceConfigBuilder> buildServiceConfigBuilder(AnnotatedHttpServiceExtensions extensions) {
        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlerFunctionBuilder.addAll(extensions.exceptionHandlers()).build();
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverterFunctionBuilder.addAll(extensions.requestConverters()).build();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverterFunctionBuilder.addAll(extensions.responseConverters()).build();

        final List<AnnotatedHttpServiceElement> elements =
                AnnotatedHttpServiceFactory.find(pathPrefix, service, exceptionHandlerFunctions,
                                                 requestConverterFunctions, responseConverterFunctions);
        return elements.stream().map(element -> {
            final HttpService decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.decorator());
            return defaultServiceConfigSetters.toServiceConfigBuilder(element.route(), decoratedService);
        }).collect(toImmutableList());
    }
}
