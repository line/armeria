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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.channel.EventLoopGroup;

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
public final class VirtualHostAnnotatedServiceBindingBuilder extends AbstractAnnotatedServiceConfigSetters {

    private final VirtualHostBuilder virtualHostBuilder;

    VirtualHostAnnotatedServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = virtualHostBuilder;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.pathPrefix(pathPrefix);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return (VirtualHostAnnotatedServiceBindingBuilder)
                super.responseConverters(responseConverterFunctions);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return (VirtualHostAnnotatedServiceBindingBuilder)
                super.responseConverters(responseConverterFunctions);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestConverters(requestConverterFunctions);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestConverters(requestConverterFunctions);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        return (VirtualHostAnnotatedServiceBindingBuilder)
                super.useBlockingTaskExecutor(useBlockingTaskExecutor);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder queryDelimiter(String delimiter) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.queryDelimiter(delimiter);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.decorator(decorator);
    }

    @SafeVarargs
    @Override
    public final VirtualHostAnnotatedServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                                     boolean shutdownOnStop) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.accessLogWriter(accessLogWriter,
                                                                                 shutdownOnStop);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder defaultLogName(String defaultLogName) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.defaultLogName(defaultLogName);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                      shutdownOnStop);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                      shutdownOnStop);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder successFunction(SuccessFunction successFunction) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.successFunction(successFunction);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestAutoAbortDelay(Duration delay) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        return (VirtualHostAnnotatedServiceBindingBuilder)
                super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder addHeader(CharSequence name, Object value) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.addHeader(name, value);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.addHeaders(defaultHeaders);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder setHeader(CharSequence name, Object value) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.setHeader(name, value);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.setHeaders(defaultHeaders);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder errorHandler(ServiceErrorHandler serviceErrorHandler) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.errorHandler(serviceErrorHandler);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contextHook(
            Supplier<? extends AutoCloseable> contextHook) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.contextHook(contextHook);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                                                        boolean shutdownOnStop) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.serviceWorkerGroup(serviceWorkerGroup,
                                                                                    shutdownOnStop);
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder serviceWorkerGroup(int numThreads) {
        return (VirtualHostAnnotatedServiceBindingBuilder) super.serviceWorkerGroup(numThreads);
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
        service(service);
        virtualHostBuilder.addServiceConfigSetters(this);
        return virtualHostBuilder;
    }
}
