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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.metric.ServiceNaming;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceElement;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory;
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

    private boolean useBlockingTaskExecutor;
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

    /**
     * Sets whether the service executes service methods using the blocking executor. By default, service
     * methods are executed directly on the event loop for implementing fully asynchronous services. If your
     * service uses blocking logic, you should either execute such logic in a separate thread using something
     * like {@link Executors#newCachedThreadPool()} or enable this setting.
     */
    public AnnotatedServiceBindingBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    @Override
    @SafeVarargs
    public final AnnotatedServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
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

    @Override
    public AnnotatedServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return this;
    }

    @Override
    public AnnotatedServiceBindingBuilder defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
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
     * {@link AnnotatedServiceExtensions} to the {@link ServerBuilder}.
     *
     * @param extensions the {@link AnnotatedServiceExtensions} at the server level.
     */
    List<ServiceConfigBuilder> buildServiceConfigBuilder(AnnotatedServiceExtensions extensions) {
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverterFunctionBuilder.addAll(extensions.requestConverters()).build();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverterFunctionBuilder.addAll(extensions.responseConverters()).build();
        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlerFunctionBuilder.addAll(extensions.exceptionHandlers()).build();

        assert service != null;

        final List<AnnotatedServiceElement> elements =
                AnnotatedServiceFactory.find(pathPrefix, service, useBlockingTaskExecutor,
                                             requestConverterFunctions, responseConverterFunctions,
                                             exceptionHandlerFunctions);
        return elements.stream().map(element -> {
            final HttpService decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.decorator());
            return defaultServiceConfigSetters.toServiceConfigBuilder(element.route(), decoratedService);
        }).collect(toImmutableList());
    }
}
