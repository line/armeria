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
 * A {@link VirtualHostContextPathAnnotatedServiceConfigSetters} builder which configures
 * an {@link AnnotatedService} under a set of context paths.
 */
@UnstableApi
public final class VirtualHostContextPathAnnotatedServiceConfigSetters
        extends AbstractContextPathAnnotatedServiceConfigSetters<VirtualHostBuilder> {

    VirtualHostContextPathAnnotatedServiceConfigSetters(
            AbstractContextPathServicesBuilder<VirtualHostBuilder> builder) {
        super(builder);
    }

    /**
     * Registers the given service to {@link VirtualHostContextPathServicesBuilder}
     * and returns the parent object.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     */
    @Override
    public VirtualHostContextPathServicesBuilder build(Object service) {
        return (VirtualHostContextPathServicesBuilder) super.build(service);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters pathPrefix(String pathPrefix) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.pathPrefix(pathPrefix);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.responseConverters(responseConverterFunctions);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.responseConverters(responseConverterFunctions);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.requestConverters(requestConverterFunctions);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.requestConverters(requestConverterFunctions);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters useBlockingTaskExecutor(
            boolean useBlockingTaskExecutor) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.useBlockingTaskExecutor(useBlockingTaskExecutor);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters queryDelimiter(
            String delimiter) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.queryDelimiter(delimiter);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestTimeout(
            Duration requestTimeout) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.requestTimeout(requestTimeout);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestTimeoutMillis(
            long requestTimeoutMillis) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters maxRequestLength(
            long maxRequestLength) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters verboseResponses(
            boolean verboseResponses) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.verboseResponses(verboseResponses);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters accessLogFormat(
            String accessLogFormat) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.accessLogFormat(accessLogFormat);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters accessLogWriter(
            AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.decorator(decorator);
    }

    @SafeVarargs
    @Override
    public final VirtualHostContextPathAnnotatedServiceConfigSetters decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.decorators(decorators);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.decorators(decorators);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters defaultServiceName(
            String defaultServiceName) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.defaultServiceName(defaultServiceName);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters defaultServiceNaming(
            ServiceNaming defaultServiceNaming) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters defaultLogName(
            String defaultLogName) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.defaultLogName(defaultLogName);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.blockingTaskExecutor(
                blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.blockingTaskExecutor(
                blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters blockingTaskExecutor(
            int numThreads) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters successFunction(
            SuccessFunction successFunction) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.successFunction(successFunction);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestAutoAbortDelay(
            Duration delay) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.requestAutoAbortDelay(delay);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestAutoAbortDelayMillis(
            long delayMillis) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters serviceWorkerGroup(
            EventLoopGroup serviceWorkerGroup, boolean shutdownOnStop) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters serviceWorkerGroup(int numThreads) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.serviceWorkerGroup(numThreads);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters multipartUploadsLocation(
            Path multipartUploadsLocation) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters)
                super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters addHeader(CharSequence name,
                                                                         Object value) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.addHeader(name, value);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.addHeaders(defaultHeaders);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters setHeader(CharSequence name,
                                                                         Object value) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.setHeader(name, value);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.setHeaders(defaultHeaders);
    }

    @Override
    public VirtualHostContextPathAnnotatedServiceConfigSetters errorHandler(
            ServiceErrorHandler serviceErrorHandler) {
        return (VirtualHostContextPathAnnotatedServiceConfigSetters) super.errorHandler(serviceErrorHandler);
    }
}
