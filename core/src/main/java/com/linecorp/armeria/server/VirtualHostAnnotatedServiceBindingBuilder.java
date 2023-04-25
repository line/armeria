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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceElement;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory;
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
 *   .build(new FooService())                      // Return to {@link VirtualHostBuilder}
 *   .and()                                        // Return to {@link ServerBuilder}
 *   .annotatedService(new MyDefaultHostService())
 *   .build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 * @see AnnotatedServiceBindingBuilder
 */
public final class VirtualHostAnnotatedServiceBindingBuilder implements AnnotatedServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();
    private final VirtualHostBuilder virtualHostBuilder;
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();

    @Nullable
    private String queryDelimiter;
    private boolean useBlockingTaskExecutor;
    private String pathPrefix = "/";
    @Nullable
    private Object service;

    VirtualHostAnnotatedServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = virtualHostBuilder;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunctions);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(exceptionHandlerFunctions);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.add(responseConverterFunctions);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(responseConverterFunctions);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.add(requestConverterFunctions);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(requestConverterFunctions);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    /**
     * Sets the delimiter for a query parameter value. Multiple values delimited by the specified
     * {@code delimiter} will be automatically split into a list of values.
     *
     * <p>It is disabled by default.
     *
     * <p>Note that this delimiter works only when the resolve target class type is collection and the number
     * of values of the query parameter is one. For example with the query delimiter {@code ","}:
     * <ul>
     *     <li>{@code ?query=a,b,c} will be resolved to {@code "a"}, {@code "b"} and {@code "c"}</li>
     *     <li>{@code ?query=a,b,c&query=d,e,f} will be resolved to {@code "a,b,c"} and {@code "d,e,f"}</li>
     * </ul>
     */
    @UnstableApi
    public VirtualHostAnnotatedServiceBindingBuilder queryDelimiter(String delimiter) {
        this.queryDelimiter = requireNonNull(delimiter, "delimiter");
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
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (VirtualHostAnnotatedServiceBindingBuilder) AnnotatedServiceConfigSetters.super.decorator(
                decoratingHttpServiceFunction);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    @Override
    @SafeVarargs
    public final VirtualHostAnnotatedServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor,
            boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor,
            boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        defaultServiceConfigSetters.blockingTaskExecutor(numThreads);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder successFunction(SuccessFunction successFunction) {
        defaultServiceConfigSetters.successFunction(successFunction);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        defaultServiceConfigSetters.multipartUploadsLocation(multipartUploadsLocation);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        defaultServiceConfigSetters.requestIdGenerator(requestIdGenerator);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder addHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.addHeader(name, value);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.addHeaders(defaultHeaders);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder setHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.setHeader(name, value);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.setHeaders(defaultHeaders);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder errorHandler(ServiceErrorHandler serviceErrorHandler) {
        defaultServiceConfigSetters.errorHandler(serviceErrorHandler);
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
        requireNonNull(service, "service");
        this.service = service;
        virtualHostBuilder.addServiceConfigSetters(this);
        return virtualHostBuilder;
    }

    /**
     * Builds the {@link ServiceConfigBuilder}s created with the configured
     * {@link AnnotatedServiceExtensions} to the {@link VirtualHostBuilder}.
     *
     * @param extensions the {@link AnnotatedServiceExtensions} at the virtual host level.
     * @param dependencyInjector the {@link DependencyInjector} to inject dependencies.
     */
    List<ServiceConfigBuilder> buildServiceConfigBuilder(AnnotatedServiceExtensions extensions,
                                                         DependencyInjector dependencyInjector) {
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverterFunctionBuilder.addAll(extensions.requestConverters()).build();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverterFunctionBuilder.addAll(extensions.responseConverters()).build();
        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlerFunctionBuilder.addAll(extensions.exceptionHandlers()).build();

        assert service != null;

        final List<AnnotatedServiceElement> elements =
                AnnotatedServiceFactory.find(
                        pathPrefix, service, useBlockingTaskExecutor, requestConverterFunctions,
                        responseConverterFunctions, exceptionHandlerFunctions, dependencyInjector,
                        queryDelimiter);
        return elements.stream().map(element -> {
            final HttpService decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.decorator());
            return defaultServiceConfigSetters.toServiceConfigBuilder(element.route(), decoratedService);
        }).collect(toImmutableList());
    }
}
