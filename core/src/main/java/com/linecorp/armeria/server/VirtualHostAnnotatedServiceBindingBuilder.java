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

import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding an {@link HttpService} to a virtual host fluently. This class can be instantiated
 * through {@link VirtualHostBuilder#annotatedService()}.
 *
 * <p>Call {@link #build(Object)} to build the {@link HttpService} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.virtualHost("foo.com")                       // Return a new instance of {@link VirtualHostBuilder}
 *   .annotatedService()                           // Return a new instance of this class
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
 *   .pathPrefix("/foo")
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .build(new FooService())                      // Return to {@link VirtualHostBuilder}
 *   .and()                                        // Return to {@link ServerBuilder}
 *   .annotatedService(new MyDefaultHostService())
 *   .build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 * @see AnnotatedServiceBindingBuilder
 */
public final class VirtualHostAnnotatedServiceBindingBuilder implements ServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();
    private final VirtualHostBuilder virtualHostBuilder;
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();
    private String pathPrefix = "/";

    VirtualHostAnnotatedServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = virtualHostBuilder;
    }

    /**
     * Sets the path prefix to be used for this {@link VirtualHostAnnotatedServiceBindingBuilder}.
     * @param pathPrefix string representing the path prefix.
     */
    public VirtualHostAnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     *
     * @deprecated Use {@link #exceptionHandlers(ExceptionHandlerFunction...)}.
     */
    @Deprecated
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandler(
            ExceptionHandlerFunction exceptionHandlerFunction) {
        requireNonNull(exceptionHandlerFunction, "exceptionHandlerFunction");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunction);
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction}s to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(ImmutableList.copyOf(exceptionHandlerFunctions));
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction}s to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(ImmutableList.copyOf(exceptionHandlerFunctions));
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     *
     * @deprecated Use {@link #responseConverters(ResponseConverterFunction...)}.
     */
    @Deprecated
    public VirtualHostAnnotatedServiceBindingBuilder responseConverter(
            ResponseConverterFunction responseConverterFunction) {
        requireNonNull(responseConverterFunction, "responseConverterFunction");
        responseConverterFunctionBuilder.add(responseConverterFunction);
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction}s to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(ImmutableList.copyOf(responseConverterFunctions));
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction}s to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(ImmutableList.copyOf(responseConverterFunctions));
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     *
     * @deprecated Use {@link #requestConverters(RequestConverterFunction...)}.
     */
    @Deprecated
    public VirtualHostAnnotatedServiceBindingBuilder requestConverter(
            RequestConverterFunction requestConverterFunction) {
        requireNonNull(requestConverterFunction, "requestConverterFunction");
        requestConverterFunctionBuilder.add(requestConverterFunction);
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction}s to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(ImmutableList.copyOf(requestConverterFunctions));
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction}s to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(ImmutableList.copyOf(requestConverterFunctions));
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestContentPreviewerFactory(
            ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.requestContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder responseContentPreviewerFactory(
            ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contentPreview(int length) {
        defaultServiceConfigSetters.contentPreview(length);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        defaultServiceConfigSetters.contentPreview(length, defaultCharset);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.contentPreviewerFactory(factory);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        defaultServiceConfigSetters.accessLogFormat(accessLogFormat);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                                     boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    /**
     * Registers the given service to the {@linkplain VirtualHostBuilder}.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     * @return {@link VirtualHostBuilder} to continue building {@link VirtualHost}
     */
    public VirtualHostBuilder build(Object service) {
        final List<AnnotatedHttpServiceElement> elements =
                AnnotatedHttpServiceFactory.find(pathPrefix, service,
                                                 exceptionHandlerFunctionBuilder.build(),
                                                 requestConverterFunctionBuilder.build(),
                                                 responseConverterFunctionBuilder.build());
        elements.forEach(element -> {
            final HttpService decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.decorator());
            final ServiceConfigBuilder serviceConfigBuilder =
                    defaultServiceConfigSetters.toServiceConfigBuilder(element.route(), decoratedService);
            virtualHostBuilder.addServiceConfigBuilder(serviceConfigBuilder);
        });
        return virtualHostBuilder;
    }
}
