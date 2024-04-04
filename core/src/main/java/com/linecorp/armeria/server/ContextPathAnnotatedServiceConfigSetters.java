/*
 * Copyright 2023 LINE Corporation
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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.channel.EventLoopGroup;

/**
 * A {@link ContextPathAnnotatedServiceConfigSetters} builder which configures an {@link AnnotatedService}
 * under a set of context paths.
 */
@UnstableApi
public final class ContextPathAnnotatedServiceConfigSetters
        extends AbstractContextPathAnnotatedServiceConfigSetters<ServerBuilder> {

    ContextPathAnnotatedServiceConfigSetters(AbstractContextPathServicesBuilder<ServerBuilder> builder) {
        super(builder);
    }

    /**
     * Registers the given service to {@link ContextPathServicesBuilder} and returns the parent object.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     */
    @Override
    public ContextPathServicesBuilder build(Object service) {
        return (ContextPathServicesBuilder) super.build(service);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters pathPrefix(String pathPrefix) {
        return (ContextPathAnnotatedServiceConfigSetters) super.pathPrefix(pathPrefix);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters) super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters) super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters) super.responseConverters(responseConverterFunctions);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters) super.responseConverters(responseConverterFunctions);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestConverters(requestConverterFunctions);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestConverters(requestConverterFunctions);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters useBlockingTaskExecutor(
            boolean useBlockingTaskExecutor) {
        return (ContextPathAnnotatedServiceConfigSetters)
                super.useBlockingTaskExecutor(useBlockingTaskExecutor);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters queryDelimiter(String delimiter) {
        return (ContextPathAnnotatedServiceConfigSetters) super.queryDelimiter(delimiter);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestTimeout(
            Duration requestTimeout) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestTimeout(requestTimeout);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestTimeoutMillis(
            long requestTimeoutMillis) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters maxRequestLength(
            long maxRequestLength) {
        return (ContextPathAnnotatedServiceConfigSetters) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters verboseResponses(
            boolean verboseResponses) {
        return (ContextPathAnnotatedServiceConfigSetters) super.verboseResponses(verboseResponses);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters accessLogFormat(
            String accessLogFormat) {
        return (ContextPathAnnotatedServiceConfigSetters) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters accessLogWriter(
            AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters)
                super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathAnnotatedServiceConfigSetters) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathAnnotatedServiceConfigSetters) super.decorator(decorator);
    }

    @SafeVarargs
    @Override
    public final ContextPathAnnotatedServiceConfigSetters decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ContextPathAnnotatedServiceConfigSetters) super.decorators(decorators);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ContextPathAnnotatedServiceConfigSetters) super.decorators(decorators);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters defaultServiceName(
            String defaultServiceName) {
        return (ContextPathAnnotatedServiceConfigSetters) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters defaultServiceNaming(
            ServiceNaming defaultServiceNaming) {
        return (ContextPathAnnotatedServiceConfigSetters) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters defaultLogName(
            String defaultLogName) {
        return (ContextPathAnnotatedServiceConfigSetters) super.defaultLogName(defaultLogName);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters)
                super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters)
                super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters blockingTaskExecutor(
            int numThreads) {
        return (ContextPathAnnotatedServiceConfigSetters) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters successFunction(
            SuccessFunction successFunction) {
        return (ContextPathAnnotatedServiceConfigSetters) super.successFunction(successFunction);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestAutoAbortDelay(
            Duration delay) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestAutoAbortDelay(delay);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestAutoAbortDelayMillis(
            long delayMillis) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                                                       boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters)
                super.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters serviceWorkerGroup(int numThreads) {
        return (ContextPathAnnotatedServiceConfigSetters) super.serviceWorkerGroup(numThreads);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters multipartUploadsLocation(
            Path multipartUploadsLocation) {
        return (ContextPathAnnotatedServiceConfigSetters)
                super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (ContextPathAnnotatedServiceConfigSetters) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters addHeader(CharSequence name,
                                                              Object value) {
        return (ContextPathAnnotatedServiceConfigSetters) super.addHeader(name, value);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathAnnotatedServiceConfigSetters) super.addHeaders(defaultHeaders);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters setHeader(CharSequence name,
                                                              Object value) {
        return (ContextPathAnnotatedServiceConfigSetters) super.setHeader(name, value);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathAnnotatedServiceConfigSetters) super.setHeaders(defaultHeaders);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters errorHandler(
            ServiceErrorHandler serviceErrorHandler) {
        return (ContextPathAnnotatedServiceConfigSetters) super.errorHandler(serviceErrorHandler);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters contextHook(
            Supplier<? extends AutoCloseable> contextHook) {
        return (ContextPathAnnotatedServiceConfigSetters) super.contextHook(contextHook);
    }
}
