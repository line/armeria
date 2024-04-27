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
public final class AnnotatedServiceBindingBuilder extends AbstractAnnotatedServiceConfigSetters {

    private final ServerBuilder serverBuilder;

    AnnotatedServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    @Override
    public AnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        return (AnnotatedServiceBindingBuilder) super.pathPrefix(pathPrefix);
    }

    @Override
    public AnnotatedServiceBindingBuilder exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return (AnnotatedServiceBindingBuilder) super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public AnnotatedServiceBindingBuilder exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return (AnnotatedServiceBindingBuilder) super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public AnnotatedServiceBindingBuilder responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return (AnnotatedServiceBindingBuilder) super.responseConverters(responseConverterFunctions);
    }

    @Override
    public AnnotatedServiceBindingBuilder responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return (AnnotatedServiceBindingBuilder) super.responseConverters(responseConverterFunctions);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return (AnnotatedServiceBindingBuilder) super.requestConverters(requestConverterFunctions);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return (AnnotatedServiceBindingBuilder) super.requestConverters(requestConverterFunctions);
    }

    @Override
    public AnnotatedServiceBindingBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        return (AnnotatedServiceBindingBuilder) super.useBlockingTaskExecutor(useBlockingTaskExecutor);
    }

    @Override
    public AnnotatedServiceBindingBuilder queryDelimiter(String delimiter) {
        return (AnnotatedServiceBindingBuilder) super.queryDelimiter(delimiter);
    }

    @Override
    public AnnotatedServiceBindingBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (AnnotatedServiceBindingBuilder) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public AnnotatedServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (AnnotatedServiceBindingBuilder) super.decorator(decorator);
    }

    @SafeVarargs
    @Override
    public final AnnotatedServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (AnnotatedServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public AnnotatedServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (AnnotatedServiceBindingBuilder) super.decorators(decorators);
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
    public AnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return (AnnotatedServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public AnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                          boolean shutdownOnStop) {
        return (AnnotatedServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public AnnotatedServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        return (AnnotatedServiceBindingBuilder) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public AnnotatedServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (AnnotatedServiceBindingBuilder) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public AnnotatedServiceBindingBuilder defaultLogName(String defaultLogName) {
        return (AnnotatedServiceBindingBuilder) super.defaultLogName(defaultLogName);
    }

    @Override
    public AnnotatedServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (AnnotatedServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                           shutdownOnStop);
    }

    @Override
    public AnnotatedServiceBindingBuilder blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                               boolean shutdownOnStop) {
        return (AnnotatedServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                           shutdownOnStop);
    }

    @Override
    public AnnotatedServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        return (AnnotatedServiceBindingBuilder) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public AnnotatedServiceBindingBuilder successFunction(SuccessFunction successFunction) {
        return (AnnotatedServiceBindingBuilder) super.successFunction(successFunction);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestAutoAbortDelay(Duration delay) {
        return (AnnotatedServiceBindingBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (AnnotatedServiceBindingBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public AnnotatedServiceBindingBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        return (AnnotatedServiceBindingBuilder) super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public AnnotatedServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (AnnotatedServiceBindingBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public AnnotatedServiceBindingBuilder addHeader(CharSequence name, Object value) {
        return (AnnotatedServiceBindingBuilder) super.addHeader(name, value);
    }

    @Override
    public AnnotatedServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (AnnotatedServiceBindingBuilder) super.addHeaders(defaultHeaders);
    }

    @Override
    public AnnotatedServiceBindingBuilder setHeader(CharSequence name, Object value) {
        return (AnnotatedServiceBindingBuilder) super.setHeader(name, value);
    }

    @Override
    public AnnotatedServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (AnnotatedServiceBindingBuilder) super.setHeaders(defaultHeaders);
    }

    @Override
    public AnnotatedServiceBindingBuilder errorHandler(ServiceErrorHandler serviceErrorHandler) {
        return (AnnotatedServiceBindingBuilder) super.errorHandler(serviceErrorHandler);
    }

    @Override
    public AnnotatedServiceBindingBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        return (AnnotatedServiceBindingBuilder) super.contextHook(contextHook);
    }

    @Override
    public AnnotatedServiceBindingBuilder serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                                             boolean shutdownOnStop) {
        return (AnnotatedServiceBindingBuilder) super.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
    }

    @Override
    public AnnotatedServiceBindingBuilder serviceWorkerGroup(int numThreads) {
        return (AnnotatedServiceBindingBuilder) super.serviceWorkerGroup(numThreads);
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
        service(service);
        serverBuilder.annotatedServiceBindingBuilder(this);
        return serverBuilder;
    }
}
