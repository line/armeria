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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

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

import io.netty.channel.EventLoopGroup;

@UnstableApi
abstract class AbstractAnnotatedServiceConfigSetters implements AnnotatedServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();

    @Nullable
    private String queryDelimiter;
    private boolean useBlockingTaskExecutor;
    private String pathPrefix = "/";
    @Nullable
    private Object service;
    private Set<String> contextPaths = Collections.singleton("/");

    final void service(Object service) {
        this.service = requireNonNull(service, "service");
    }

    final void contextPaths(Set<String> contextPaths) {
        this.contextPaths = requireNonNull(contextPaths, "contextPaths");
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(exceptionHandlerFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.add(responseConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(responseConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.add(requestConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(requestConverterFunctions);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
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
    public AbstractAnnotatedServiceConfigSetters queryDelimiter(String delimiter) {
        queryDelimiter = requireNonNull(delimiter, "delimiter");
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (AbstractAnnotatedServiceConfigSetters) AnnotatedServiceConfigSetters.super.decorator(
                decoratingHttpServiceFunction);
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters accessLogFormat(String accessLogFormat) {
        defaultServiceConfigSetters.accessLogFormat(accessLogFormat);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters accessLogWriter(AccessLogWriter accessLogWriter,
                                                                 boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                                      boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters blockingTaskExecutor(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.builder()
                                                                  .numThreads(numThreads)
                                                                  .build();
        return blockingTaskExecutor(executor, true);
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters successFunction(SuccessFunction successFunction) {
        defaultServiceConfigSetters.successFunction(successFunction);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestAutoAbortDelay(Duration delay) {
        defaultServiceConfigSetters.requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestAutoAbortDelayMillis(long delayMillis) {
        defaultServiceConfigSetters.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters multipartUploadsLocation(Path multipartUploadsLocation) {
        defaultServiceConfigSetters.multipartUploadsLocation(multipartUploadsLocation);
        return this;
    }

    @Override
    public ServiceConfigSetters serviceWorkerGroup(EventLoopGroup serviceWorkerGroup, boolean shutdownOnStop) {
        defaultServiceConfigSetters.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
        return this;
    }

    @Override
    public ServiceConfigSetters serviceWorkerGroup(int numThreads) {
        defaultServiceConfigSetters.serviceWorkerGroup(numThreads);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        defaultServiceConfigSetters.requestIdGenerator(requestIdGenerator);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters addHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.addHeader(name, value);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.addHeaders(defaultHeaders);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters setHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.setHeader(name, value);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.setHeaders(defaultHeaders);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters errorHandler(ServiceErrorHandler serviceErrorHandler) {
        defaultServiceConfigSetters.errorHandler(serviceErrorHandler);
        return this;
    }

    @Override
    public AbstractAnnotatedServiceConfigSetters contextHook(Supplier<? extends AutoCloseable> contextHook) {
        defaultServiceConfigSetters.contextHook(contextHook);
        return this;
    }

    /**
     * Builds the {@link ServiceConfigBuilder}s created with the configured
     * {@link AnnotatedServiceExtensions}.
     *
     * @param extensions the {@link AnnotatedServiceExtensions} of the parent.
     * @param dependencyInjector the {@link DependencyInjector} to inject dependencies.
     */
    final List<ServiceConfigBuilder> buildServiceConfigBuilder(AnnotatedServiceExtensions extensions,
                                                               DependencyInjector dependencyInjector) {
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
                                             exceptionHandlerFunctions, dependencyInjector, queryDelimiter);
        return elements.stream().flatMap(element -> {
            final HttpService decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.decorator());
            assert !contextPaths.isEmpty() : "contextPaths shouldn't be empty";
            return contextPaths.stream().map(contextPath -> {
                return defaultServiceConfigSetters.toServiceConfigBuilder(
                        element.route(), contextPath, decoratedService);
            });
        }).collect(toImmutableList());
    }
}
