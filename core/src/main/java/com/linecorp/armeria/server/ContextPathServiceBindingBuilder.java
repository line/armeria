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
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.channel.EventLoopGroup;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link ServerBuilder#contextPath(String...)}.
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
 * }</pre>
 */
@UnstableApi
public final class ContextPathServiceBindingBuilder
        extends AbstractContextPathServiceBindingBuilder<ServerBuilder> {

    ContextPathServiceBindingBuilder(AbstractContextPathServicesBuilder<ServerBuilder> builder) {
        super(builder);
    }

    @Override
    public ContextPathServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return (ContextPathServiceBindingBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public ContextPathServiceBindingBuilder requestTimeoutMillis(
            long requestTimeoutMillis) {
        return (ContextPathServiceBindingBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public ContextPathServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        return (ContextPathServiceBindingBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public ContextPathServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        return (ContextPathServiceBindingBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public ContextPathServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return (ContextPathServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public ContextPathServiceBindingBuilder accessLogWriter(
            AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServiceBindingBuilder) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServiceBindingBuilder) super.decorator(decorator);
    }

    @Override
    public ContextPathServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ContextPathServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public ContextPathServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ContextPathServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public ContextPathServiceBindingBuilder defaultServiceName(
            String defaultServiceName) {
        return (ContextPathServiceBindingBuilder) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public ContextPathServiceBindingBuilder defaultServiceNaming(
            ServiceNaming defaultServiceNaming) {
        return (ContextPathServiceBindingBuilder) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public ContextPathServiceBindingBuilder defaultLogName(String defaultLogName) {
        return (ContextPathServiceBindingBuilder) super.defaultLogName(defaultLogName);
    }

    @Override
    public ContextPathServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder)
                super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder)
                super.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        return (ContextPathServiceBindingBuilder) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public ContextPathServiceBindingBuilder successFunction(
            SuccessFunction successFunction) {
        return (ContextPathServiceBindingBuilder) super.successFunction(successFunction);
    }

    @Override
    public ContextPathServiceBindingBuilder requestAutoAbortDelay(Duration delay) {
        return (ContextPathServiceBindingBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public ContextPathServiceBindingBuilder requestAutoAbortDelayMillis(
            long delayMillis) {
        return (ContextPathServiceBindingBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public ContextPathServiceBindingBuilder multipartUploadsLocation(
            Path multipartUploadsLocation) {
        return (ContextPathServiceBindingBuilder) super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public ContextPathServiceBindingBuilder serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                                               boolean shutdownOnStop) {
        return (ContextPathServiceBindingBuilder) super.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
    }

    @Override
    public ContextPathServiceBindingBuilder serviceWorkerGroup(int numThreads) {
        return (ContextPathServiceBindingBuilder) super.serviceWorkerGroup(numThreads);
    }

    @Override
    public ContextPathServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (ContextPathServiceBindingBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public ContextPathServiceBindingBuilder addHeader(CharSequence name, Object value) {
        return (ContextPathServiceBindingBuilder) super.addHeader(name, value);
    }

    @Override
    public ContextPathServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathServiceBindingBuilder) super.addHeaders(defaultHeaders);
    }

    @Override
    public ContextPathServiceBindingBuilder setHeader(CharSequence name, Object value) {
        return (ContextPathServiceBindingBuilder) super.setHeader(name, value);
    }

    @Override
    public ContextPathServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (ContextPathServiceBindingBuilder) super.setHeaders(defaultHeaders);
    }

    @Override
    public ContextPathServiceBindingBuilder errorHandler(
            ServiceErrorHandler serviceErrorHandler) {
        return (ContextPathServiceBindingBuilder) super.errorHandler(serviceErrorHandler);
    }

    @Override
    public ContextPathServiceBindingBuilder path(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.path(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder pathPrefix(String prefix) {
        return (ContextPathServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public ContextPathServiceBindingBuilder get(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.get(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder post(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.post(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder put(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.put(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder patch(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder delete(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder options(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.options(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder head(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.head(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder trace(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder connect(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder methods(HttpMethod... methods) {
        return (ContextPathServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public ContextPathServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (ContextPathServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public ContextPathServiceBindingBuilder consumes(MediaType... consumeTypes) {
        return (ContextPathServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (ContextPathServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder produces(MediaType... produceTypes) {
        return (ContextPathServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder produces(Iterable<MediaType> produceTypes) {
        return (ContextPathServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public ContextPathServiceBindingBuilder matchesParams(String... paramPredicates) {
        return (ContextPathServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder matchesParams(
            Iterable<String> paramPredicates) {
        return (ContextPathServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder matchesParams(String paramName,
                                                          Predicate<? super String> valuePredicate) {
        return (ContextPathServiceBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public ContextPathServiceBindingBuilder matchesHeaders(String... headerPredicates) {
        return (ContextPathServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder matchesHeaders(
            Iterable<String> headerPredicates) {
        return (ContextPathServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathServiceBindingBuilder matchesHeaders(CharSequence headerName,
                                                           Predicate<? super String> valuePredicate) {
        return (ContextPathServiceBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public ContextPathServiceBindingBuilder addRoute(Route route) {
        return (ContextPathServiceBindingBuilder) super.addRoute(route);
    }

    @Override
    public ContextPathServiceBindingBuilder exclude(String pathPattern) {
        return (ContextPathServiceBindingBuilder) super.exclude(pathPattern);
    }

    @Override
    public ContextPathServiceBindingBuilder exclude(Route excludedRoute) {
        return (ContextPathServiceBindingBuilder) super.exclude(excludedRoute);
    }

    @Override
    public ContextPathServiceBindingBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        return (ContextPathServiceBindingBuilder) super.contextHook(contextHook);
    }

    /**
     * Sets the {@link HttpService} and returns the object that this
     * {@link ContextPathServicesBuilder} was created from.
     */
    @Override
    public ContextPathServicesBuilder build(HttpService service) {
        return (ContextPathServicesBuilder) super.build(service);
    }
}
