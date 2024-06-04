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
abstract class AbstractAnnotatedServiceConfigSetters<SELF extends AbstractAnnotatedServiceConfigSetters<SELF>>
        implements AnnotatedServiceConfigSetters<SELF> {

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

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    @Override
    public SELF pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return self();
    }

    @Override
    public SELF exceptionHandlers(
            ExceptionHandlerFunction... exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunctions);
        return self();
    }

    @Override
    public SELF exceptionHandlers(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        exceptionHandlerFunctionBuilder.addAll(exceptionHandlerFunctions);
        return self();
    }

    @Override
    public SELF responseConverters(
            ResponseConverterFunction... responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.add(responseConverterFunctions);
        return self();
    }

    @Override
    public SELF responseConverters(
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        responseConverterFunctionBuilder.addAll(responseConverterFunctions);
        return self();
    }

    @Override
    public SELF requestConverters(
            RequestConverterFunction... requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.add(requestConverterFunctions);
        return self();
    }

    @Override
    public SELF requestConverters(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requestConverterFunctionBuilder.addAll(requestConverterFunctions);
        return self();
    }

    @Override
    public SELF useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return self();
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
    public SELF queryDelimiter(String delimiter) {
        queryDelimiter = requireNonNull(delimiter, "delimiter");
        return self();
    }

    @Override
    public SELF decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return self();
    }

    @Override
    public SELF decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return self();
    }

    @Override
    public SELF decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return self();
    }

    @Override
    public SELF requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return self();
    }

    @Override
    public SELF requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return self();
    }

    @Override
    public SELF maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return self();
    }

    @Override
    public SELF verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return self();
    }

    @Override
    public SELF accessLogFormat(String accessLogFormat) {
        defaultServiceConfigSetters.accessLogFormat(accessLogFormat);
        return self();
    }

    @Override
    public SELF accessLogWriter(AccessLogWriter accessLogWriter,
                                                                 boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return self();
    }

    @Override
    public SELF defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return self();
    }

    @Override
    public SELF defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return self();
    }

    @Override
    public SELF defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
        return self();
    }

    @Override
    public SELF blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return self();
    }

    @Override
    public SELF blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                                      boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return self();
    }

    @Override
    public SELF blockingTaskExecutor(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.builder()
                                                                  .numThreads(numThreads)
                                                                  .build();
        return blockingTaskExecutor(executor, true);
    }

    @Override
    public SELF successFunction(SuccessFunction successFunction) {
        defaultServiceConfigSetters.successFunction(successFunction);
        return self();
    }

    @Override
    public SELF requestAutoAbortDelay(Duration delay) {
        defaultServiceConfigSetters.requestAutoAbortDelay(delay);
        return self();
    }

    @Override
    public SELF requestAutoAbortDelayMillis(long delayMillis) {
        defaultServiceConfigSetters.requestAutoAbortDelayMillis(delayMillis);
        return self();
    }

    @Override
    public SELF multipartUploadsLocation(Path multipartUploadsLocation) {
        defaultServiceConfigSetters.multipartUploadsLocation(multipartUploadsLocation);
        return self();
    }

    @UnstableApi
    @Override
    public SELF multipartRemovalStrategy(
            MultipartRemovalStrategy removalStrategy) {
        defaultServiceConfigSetters.multipartRemovalStrategy(removalStrategy);
        return self();
    }

    @Override
    public SELF serviceWorkerGroup(EventLoopGroup serviceWorkerGroup, boolean shutdownOnStop) {
        defaultServiceConfigSetters.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
        return self();
    }

    @Override
    public SELF serviceWorkerGroup(int numThreads) {
        defaultServiceConfigSetters.serviceWorkerGroup(numThreads);
        return self();
    }

    @Override
    public SELF requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        defaultServiceConfigSetters.requestIdGenerator(requestIdGenerator);
        return self();
    }

    @Override
    public SELF addHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.addHeader(name, value);
        return self();
    }

    @Override
    public SELF addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.addHeaders(defaultHeaders);
        return self();
    }

    @Override
    public SELF setHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.setHeader(name, value);
        return self();
    }

    @Override
    public SELF setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.setHeaders(defaultHeaders);
        return self();
    }

    @Override
    public SELF errorHandler(ServiceErrorHandler serviceErrorHandler) {
        defaultServiceConfigSetters.errorHandler(serviceErrorHandler);
        return self();
    }

    @Override
    public SELF contextHook(Supplier<? extends AutoCloseable> contextHook) {
        defaultServiceConfigSetters.contextHook(contextHook);
        return self();
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
