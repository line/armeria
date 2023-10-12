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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link ServerBuilder#contextPath(String...)} or {@link VirtualHostBuilder#contextPath(String...)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return to the {@link ServerBuilder}
 * or {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * Server.builder()
 *       .contextPath("/v1", "/v2")
 *       .route()
 *       .get("/service1")
 *       .build(service1) // served under "/v1/service1" and "/v2/service1"
 *       .and()
 *       .virtualHost("foo.com")
 *       .contextPath("/v3")
 *       .route()
 *       .get("/service2")
 *       .build(service2); // served under "/v3/service2"
 * }</pre>
 *
 * @see VirtualHostServiceBindingBuilder
 * @param <T> the type this service will be added to.
 */
@UnstableApi
public final class ContextPathServiceBindingBuilder<T extends ServiceConfigsBuilder>
        extends AbstractServiceBindingBuilder {

    private final ContextPathServicesBuilder<T> contextPathServicesBuilder;

    ContextPathServiceBindingBuilder(ContextPathServicesBuilder<T> builder) {
        super(builder.contextPaths());
        contextPathServicesBuilder = builder;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> requestTimeout(Duration requestTimeout) {
        return (ContextPathServiceBindingBuilder<T>) super.requestTimeout(requestTimeout);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> requestTimeoutMillis(long requestTimeoutMillis) {
        return (ContextPathServiceBindingBuilder<T>) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> maxRequestLength(long maxRequestLength) {
        return (ContextPathServiceBindingBuilder<T>) super.maxRequestLength(maxRequestLength);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> verboseResponses(boolean verboseResponses) {
        return (ContextPathServiceBindingBuilder<T>) super.verboseResponses(verboseResponses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> accessLogFormat(String accessLogFormat) {
        return (ContextPathServiceBindingBuilder<T>) super.accessLogFormat(accessLogFormat);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> accessLogWriter(AccessLogWriter accessLogWriter,
                                                               boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder<T>) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServiceBindingBuilder<T>) super.decorator(decoratingHttpServiceFunction);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServiceBindingBuilder<T>) super.decorator(decorator);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ContextPathServiceBindingBuilder<T>) super.decorators(decorators);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ContextPathServiceBindingBuilder<T>) super.decorators(decorators);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> defaultServiceName(String defaultServiceName) {
        return (ContextPathServiceBindingBuilder<T>) super.defaultServiceName(defaultServiceName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (ContextPathServiceBindingBuilder<T>) super.defaultServiceNaming(defaultServiceNaming);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> defaultLogName(String defaultLogName) {
        return (ContextPathServiceBindingBuilder<T>) super.defaultLogName(defaultLogName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder<T>) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                shutdownOnStop);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                                    boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder<T>) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                shutdownOnStop);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> blockingTaskExecutor(int numThreads) {
        return (ContextPathServiceBindingBuilder<T>) super.blockingTaskExecutor(numThreads);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> successFunction(SuccessFunction successFunction) {
        return (ContextPathServiceBindingBuilder<T>) super.successFunction(successFunction);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> requestAutoAbortDelay(Duration delay) {
        return (ContextPathServiceBindingBuilder<T>) super.requestAutoAbortDelay(delay);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> requestAutoAbortDelayMillis(long delayMillis) {
        return (ContextPathServiceBindingBuilder<T>) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> multipartUploadsLocation(Path multipartUploadsLocation) {
        return (ContextPathServiceBindingBuilder<T>) super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (ContextPathServiceBindingBuilder<T>) super.requestIdGenerator(requestIdGenerator);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> addHeader(CharSequence name, Object value) {
        return (ContextPathServiceBindingBuilder<T>) super.addHeader(name, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathServiceBindingBuilder<T>) super.addHeaders(defaultHeaders);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> setHeader(CharSequence name, Object value) {
        return (ContextPathServiceBindingBuilder<T>) super.setHeader(name, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathServiceBindingBuilder<T>) super.setHeaders(defaultHeaders);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> errorHandler(ServiceErrorHandler serviceErrorHandler) {
        return (ContextPathServiceBindingBuilder<T>) super.errorHandler(serviceErrorHandler);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> path(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.path(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> pathPrefix(String prefix) {
        return (ContextPathServiceBindingBuilder<T>) super.pathPrefix(prefix);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> get(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.get(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> post(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.post(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> put(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.put(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> patch(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.patch(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> delete(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.delete(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> options(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.options(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> head(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.head(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> trace(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.trace(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> connect(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.connect(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> methods(HttpMethod... methods) {
        return (ContextPathServiceBindingBuilder<T>) super.methods(methods);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> methods(Iterable<HttpMethod> methods) {
        return (ContextPathServiceBindingBuilder<T>) super.methods(methods);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> consumes(MediaType... consumeTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> consumes(Iterable<MediaType> consumeTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> produces(MediaType... produceTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.produces(produceTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> produces(Iterable<MediaType> produceTypes) {
        return (ContextPathServiceBindingBuilder<T>) super.produces(produceTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> matchesParams(String... paramPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> matchesParams(Iterable<String> paramPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> matchesParams(String paramName,
                                                             Predicate<? super String> valuePredicate) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesParams(paramName, valuePredicate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> matchesHeaders(String... headerPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> matchesHeaders(Iterable<String> headerPredicates) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> matchesHeaders(CharSequence headerName,
                                                              Predicate<? super String> valuePredicate) {
        return (ContextPathServiceBindingBuilder<T>) super.matchesHeaders(headerName, valuePredicate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> addRoute(Route route) {
        return (ContextPathServiceBindingBuilder<T>) super.addRoute(route);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> exclude(String pathPattern) {
        return (ContextPathServiceBindingBuilder<T>) super.exclude(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathServiceBindingBuilder<T> exclude(Route excludedRoute) {
        return (ContextPathServiceBindingBuilder<T>) super.exclude(excludedRoute);
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        contextPathServicesBuilder.addServiceConfigSetters(serviceConfigBuilder);
    }

    /**
     * Sets the {@link HttpService} and returns the object that this
     * {@link ContextPathServiceBindingBuilder} was created from.
     */
    public ContextPathServicesBuilder<T> build(HttpService service) {
        requireNonNull(service, "service");
        build0(service);
        return contextPathServicesBuilder;
    }
}
