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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * An {@link AbstractAnnotatedServiceConfigSetters} builder which configures an {@link AnnotatedService}.
 *
 * @param <T> the type of object to be returned once the builder is built
 */
final class ContextPathAnnotatedServiceConfigSetters<T> extends AbstractAnnotatedServiceConfigSetters {

    private final ContextPathServicesBuilder<T> builder;

    ContextPathAnnotatedServiceConfigSetters(ContextPathServicesBuilder<T> builder) {
        this.builder = builder;
    }

    /**
     * Registers the given service to {@link T} and returns the parent object.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     */
    public ContextPathServicesBuilder<T> build(Object service) {
        requireNonNull(service, "service");
        service(service);
        builder.addServiceConfigSetters(this);
        return builder;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> pathPrefix(String pathPrefix) {
        return (ContextPathAnnotatedServiceConfigSetters<T>) super.pathPrefix(pathPrefix);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.exceptionHandlers(exceptionHandlerFunctions);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.responseConverters(responseConverterFunctions);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.responseConverters(responseConverterFunctions);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.requestConverters(requestConverterFunctions);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.requestConverters(requestConverterFunctions);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> useBlockingTaskExecutor(
            boolean useBlockingTaskExecutor) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.useBlockingTaskExecutor(useBlockingTaskExecutor);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> queryDelimiter(String delimiter) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.queryDelimiter(delimiter);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestTimeout(Duration requestTimeout) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.requestTimeout(requestTimeout);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestTimeoutMillis(long requestTimeoutMillis) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> maxRequestLength(long maxRequestLength) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.maxRequestLength(maxRequestLength);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> verboseResponses(boolean verboseResponses) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.verboseResponses(verboseResponses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> accessLogFormat(String accessLogFormat) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.accessLogFormat(accessLogFormat);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> accessLogWriter(AccessLogWriter accessLogWriter,
                                                                       boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.decorator(decoratingHttpServiceFunction);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.decorator(decorator);
    }

    @SuppressWarnings("unchecked")
    @SafeVarargs
    @Override
    public final ContextPathAnnotatedServiceConfigSetters<T> decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ContextPathAnnotatedServiceConfigSetters<T>) super.decorators(decorators);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.decorators(decorators);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> defaultServiceName(String defaultServiceName) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.defaultServiceName(defaultServiceName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> defaultServiceNaming(
            ServiceNaming defaultServiceNaming) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.defaultServiceNaming(defaultServiceNaming);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> defaultLogName(String defaultLogName) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.defaultLogName(defaultLogName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> blockingTaskExecutor(int numThreads) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.blockingTaskExecutor(numThreads);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> successFunction(
            SuccessFunction successFunction) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.successFunction(successFunction);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestAutoAbortDelay(Duration delay) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.requestAutoAbortDelay(delay);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestAutoAbortDelayMillis(long delayMillis) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.requestAutoAbortDelayMillis(delayMillis);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> multipartUploadsLocation(
            Path multipartUploadsLocation) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)
                super.requestIdGenerator(requestIdGenerator);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> addHeader(CharSequence name, Object value) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.addHeader(name, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.addHeaders(defaultHeaders);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> setHeader(CharSequence name, Object value) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.setHeader(name, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.setHeaders(defaultHeaders);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathAnnotatedServiceConfigSetters<T> errorHandler(
            ServiceErrorHandler serviceErrorHandler) {
        return (ContextPathAnnotatedServiceConfigSetters<T>)  super.errorHandler(serviceErrorHandler);
    }
}