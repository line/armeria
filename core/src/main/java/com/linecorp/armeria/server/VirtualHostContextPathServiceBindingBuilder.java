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
 * {@link VirtualHostBuilder#contextPath(String...)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return
 * to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * Server.builder()
 *       .virtualHost("foo.com")
 *       .contextPath("/v3")
 *       .route()
 *       .get("/service2")
 *       .build(service2); // served under "/v3/service2"
 * }</pre>
 */
@UnstableApi
public final class VirtualHostContextPathServiceBindingBuilder
        extends AbstractContextPathServiceBindingBuilder<VirtualHostBuilder> {

    VirtualHostContextPathServiceBindingBuilder(
            AbstractContextPathServicesBuilder<VirtualHostBuilder> builder) {
        super(builder);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder requestTimeout(
            Duration requestTimeout) {
        return (VirtualHostContextPathServiceBindingBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder requestTimeoutMillis(
            long requestTimeoutMillis) {
        return (VirtualHostContextPathServiceBindingBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder maxRequestLength(
            long maxRequestLength) {
        return (VirtualHostContextPathServiceBindingBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder verboseResponses(
            boolean verboseResponses) {
        return (VirtualHostContextPathServiceBindingBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder accessLogFormat(
            String accessLogFormat) {
        return (VirtualHostContextPathServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder accessLogWriter(
            AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return (VirtualHostContextPathServiceBindingBuilder) super.accessLogWriter(accessLogWriter,
                                                                                   shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (VirtualHostContextPathServiceBindingBuilder) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (VirtualHostContextPathServiceBindingBuilder) super.decorator(decorator);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (VirtualHostContextPathServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (VirtualHostContextPathServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder defaultServiceName(
            String defaultServiceName) {
        return (VirtualHostContextPathServiceBindingBuilder) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder defaultServiceNaming(
            ServiceNaming defaultServiceNaming) {
        return (VirtualHostContextPathServiceBindingBuilder) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder defaultLogName(String defaultLogName) {
        return (VirtualHostContextPathServiceBindingBuilder) super.defaultLogName(defaultLogName);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownOnStop) {
        return (VirtualHostContextPathServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                        shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder blockingTaskExecutor(
            BlockingTaskExecutor blockingTaskExecutor, boolean shutdownOnStop) {
        return (VirtualHostContextPathServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                                        shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        return (VirtualHostContextPathServiceBindingBuilder) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder successFunction(
            SuccessFunction successFunction) {
        return (VirtualHostContextPathServiceBindingBuilder) super.successFunction(successFunction);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder requestAutoAbortDelay(Duration delay) {
        return (VirtualHostContextPathServiceBindingBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder requestAutoAbortDelayMillis(
            long delayMillis) {
        return (VirtualHostContextPathServiceBindingBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder multipartUploadsLocation(
            Path multipartUploadsLocation) {
        return (VirtualHostContextPathServiceBindingBuilder)
                super.multipartUploadsLocation(multipartUploadsLocation);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                                                          boolean shutdownOnStop) {
        return (VirtualHostContextPathServiceBindingBuilder) super.serviceWorkerGroup(serviceWorkerGroup,
                                                                                      shutdownOnStop);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder serviceWorkerGroup(int numThreads) {
        return (VirtualHostContextPathServiceBindingBuilder) super.serviceWorkerGroup(numThreads);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        return (VirtualHostContextPathServiceBindingBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder addHeader(CharSequence name,
                                                                 Object value) {
        return (VirtualHostContextPathServiceBindingBuilder) super.addHeader(name, value);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (VirtualHostContextPathServiceBindingBuilder) super.addHeaders(defaultHeaders);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder setHeader(CharSequence name,
                                                                 Object value) {
        return (VirtualHostContextPathServiceBindingBuilder) super.setHeader(name, value);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        return (VirtualHostContextPathServiceBindingBuilder) super.setHeaders(defaultHeaders);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder errorHandler(
            ServiceErrorHandler serviceErrorHandler) {
        return (VirtualHostContextPathServiceBindingBuilder) super.errorHandler(serviceErrorHandler);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder contextHook(
            Supplier<? extends AutoCloseable> contextHook) {
        return (VirtualHostContextPathServiceBindingBuilder) super.contextHook(contextHook);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder path(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.path(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder pathPrefix(String prefix) {
        return (VirtualHostContextPathServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder get(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.get(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder post(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.post(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder put(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.put(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder patch(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder delete(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder options(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.options(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder head(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.head(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder trace(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder connect(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder methods(HttpMethod... methods) {
        return (VirtualHostContextPathServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (VirtualHostContextPathServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder consumes(MediaType... consumeTypes) {
        return (VirtualHostContextPathServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder consumes(
            Iterable<MediaType> consumeTypes) {
        return (VirtualHostContextPathServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder produces(MediaType... produceTypes) {
        return (VirtualHostContextPathServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder produces(
            Iterable<MediaType> produceTypes) {
        return (VirtualHostContextPathServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder matchesParams(
            String... paramPredicates) {
        return (VirtualHostContextPathServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder matchesParams(
            Iterable<String> paramPredicates) {
        return (VirtualHostContextPathServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder matchesParams(
            String paramName, Predicate<? super String> valuePredicate) {
        return (VirtualHostContextPathServiceBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder matchesHeaders(
            String... headerPredicates) {
        return (VirtualHostContextPathServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder matchesHeaders(
            Iterable<String> headerPredicates) {
        return (VirtualHostContextPathServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder matchesHeaders(
            CharSequence headerName,
            Predicate<? super String> valuePredicate) {
        return (VirtualHostContextPathServiceBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder addRoute(Route route) {
        return (VirtualHostContextPathServiceBindingBuilder) super.addRoute(route);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder exclude(String pathPattern) {
        return (VirtualHostContextPathServiceBindingBuilder) super.exclude(pathPattern);
    }

    @Override
    public VirtualHostContextPathServiceBindingBuilder exclude(Route excludedRoute) {
        return (VirtualHostContextPathServiceBindingBuilder) super.exclude(excludedRoute);
    }

    /**
     * Sets the {@link HttpService} and returns the object that this
     * {@link VirtualHostContextPathServicesBuilder} was created from.
     */
    @Override
    public VirtualHostContextPathServicesBuilder build(HttpService service) {
        return (VirtualHostContextPathServicesBuilder) super.build(service);
    }
}
