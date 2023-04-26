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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.VirtualHostBuilder.ensureNoPseudoHeader;
import static com.linecorp.armeria.server.VirtualHostBuilder.mergeDefaultHeaders;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

final class ServiceConfigBuilder implements ServiceConfigSetters {

    private final Route route;
    private final HttpService service;

    @Nullable
    private Route mappedRoute;
    @Nullable
    private String defaultServiceName;
    @Nullable
    private ServiceNaming defaultServiceNaming;
    @Nullable
    private String defaultLogName;
    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private AccessLogWriter accessLogWriter;
    @Nullable
    private BlockingTaskExecutor blockingTaskExecutor;
    @Nullable
    private SuccessFunction successFunction;
    @Nullable
    private Path multipartUploadsLocation;
    @Nullable
    private ServiceErrorHandler serviceErrorHandler;
    private final List<ShutdownSupport> shutdownSupports = new ArrayList<>();
    private final HttpHeadersBuilder defaultHeaders = HttpHeaders.builder();
    @Nullable
    private Function<? super RoutingContext, ? extends RequestId> requestIdGenerator;

    ServiceConfigBuilder(Route route, HttpService service) {
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
    }

    void addMappedRoute(Route mappedRoute) {
        this.mappedRoute = requireNonNull(mappedRoute, "mappedRoute");
    }

    @Override
    public ServiceConfigBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requestTimeout.toMillis());
    }

    @Override
    public ServiceConfigBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        return this;
    }

    @Override
    public ServiceConfigBuilder maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = maxRequestLength;
        return this;
    }

    @Override
    public ServiceConfigBuilder verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    @Override
    public ServiceConfigBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        if (this.accessLogWriter != null) {
            this.accessLogWriter = this.accessLogWriter.andThen(accessLogWriter);
        } else {
            this.accessLogWriter = accessLogWriter;
        }
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(accessLogWriter));
        }
        return this;
    }

    @Override
    public ServiceConfigBuilder accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               false);
    }

    @Override
    public ServiceConfigBuilder decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceConfigBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SafeVarargs
    public final ServiceConfigBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceConfigBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceConfigBuilder defaultLogName(String defaultLogName) {
        this.defaultLogName = requireNonNull(defaultLogName, "defaultLogName");
        return this;
    }

    @Override
    public ServiceConfigBuilder blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                                     boolean shutdownOnStop) {
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        return blockingTaskExecutor(BlockingTaskExecutor.of(blockingTaskExecutor), shutdownOnStop);
    }

    @Override
    public ServiceConfigBuilder blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                     boolean shutdownOnStop) {
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(blockingTaskExecutor));
        }
        return this;
    }

    @Override
    public ServiceConfigBuilder blockingTaskExecutor(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.builder()
                                                                  .numThreads(numThreads)
                                                                  .build();
        return blockingTaskExecutor(executor, true);
    }

    @Override
    public ServiceConfigBuilder successFunction(
            SuccessFunction successFunction) {
        this.successFunction = requireNonNull(successFunction, "successFunction");
        return this;
    }

    @Override
    public ServiceConfigBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        this.multipartUploadsLocation = multipartUploadsLocation;
        return this;
    }

    @Override
    public ServiceConfigBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        this.requestIdGenerator = requireNonNull(requestIdGenerator, "requestIdGenerator");
        return this;
    }

    @Override
    public ServiceConfigBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureNoPseudoHeader(name);
        defaultHeaders.addObject(name, value);
        return this;
    }

    @Override
    public ServiceConfigBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        requireNonNull(defaultHeaders, "defaultHeaders");
        ensureNoPseudoHeader(defaultHeaders);
        this.defaultHeaders.addObject(defaultHeaders);
        return this;
    }

    @Override
    public ServiceConfigBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureNoPseudoHeader(name);
        defaultHeaders.setObject(name, value);
        return this;
    }

    @Override
    public ServiceConfigBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        requireNonNull(defaultHeaders, "defaultHeaders");
        ensureNoPseudoHeader(defaultHeaders);
        this.defaultHeaders.setObject(defaultHeaders);
        return this;
    }

    @Override
    public ServiceConfigBuilder errorHandler(ServiceErrorHandler serviceErrorHandler) {
        requireNonNull(serviceErrorHandler, "serviceErrorHandler");
        this.serviceErrorHandler = serviceErrorHandler;
        return this;
    }

    @Override
    public ServiceConfigBuilder defaultServiceName(String defaultServiceName) {
        requireNonNull(defaultServiceName, "defaultServiceName");
        this.defaultServiceName = defaultServiceName;
        defaultServiceNaming = ServiceNaming.of(defaultServiceName);
        return this;
    }

    @Override
    public ServiceConfigBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceName = null;
        this.defaultServiceNaming = requireNonNull(defaultServiceNaming, "defaultServiceNaming");
        return this;
    }

    void shutdownSupports(List<ShutdownSupport> shutdownSupports) {
        requireNonNull(shutdownSupports, "shutdownSupports");
        this.shutdownSupports.addAll(shutdownSupports);
    }

    void defaultHeaders(HttpHeaders defaultHeaders) {
        requireNonNull(defaultHeaders, "defaultHeaders");
        defaultHeaders.forEach((name, value) -> this.defaultHeaders.add(name, value));
    }

    ServiceConfig build(ServiceNaming defaultServiceNaming,
                        long defaultRequestTimeoutMillis,
                        long defaultMaxRequestLength,
                        boolean defaultVerboseResponses,
                        AccessLogWriter defaultAccessLogWriter,
                        BlockingTaskExecutor defaultBlockingTaskExecutor,
                        SuccessFunction defaultSuccessFunction,
                        Path defaultMultipartUploadsLocation, HttpHeaders virtualHostDefaultHeaders,
                        Function<? super RoutingContext, ? extends RequestId> defaultRequestIdGenerator,
                        ServiceErrorHandler defaultServiceErrorHandler,
                        @Nullable UnhandledExceptionsReporter unhandledExceptionsReporter) {
        ServiceErrorHandler errorHandler =
                serviceErrorHandler != null ? serviceErrorHandler.orElse(defaultServiceErrorHandler)
                                            : defaultServiceErrorHandler;
        if (unhandledExceptionsReporter != null) {
            errorHandler = new ExceptionReportingServiceErrorHandler(errorHandler,
                                                                     unhandledExceptionsReporter);
        }

        return new ServiceConfig(
                route, mappedRoute == null ? route : mappedRoute,
                service, defaultLogName, defaultServiceName,
                this.defaultServiceNaming != null ? this.defaultServiceNaming : defaultServiceNaming,
                requestTimeoutMillis != null ? requestTimeoutMillis : defaultRequestTimeoutMillis,
                maxRequestLength != null ? maxRequestLength : defaultMaxRequestLength,
                verboseResponses != null ? verboseResponses : defaultVerboseResponses,
                accessLogWriter != null ? accessLogWriter : defaultAccessLogWriter,
                blockingTaskExecutor != null ? blockingTaskExecutor : defaultBlockingTaskExecutor,
                successFunction != null ? successFunction : defaultSuccessFunction,
                multipartUploadsLocation != null ? multipartUploadsLocation : defaultMultipartUploadsLocation,
                ImmutableList.copyOf(shutdownSupports),
                mergeDefaultHeaders(virtualHostDefaultHeaders.toBuilder(), defaultHeaders.build()),
                requestIdGenerator != null ? requestIdGenerator : defaultRequestIdGenerator, errorHandler);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("route", route)
                          .add("service", service)
                          .add("defaultServiceNaming", defaultServiceNaming)
                          .add("requestTimeoutMillis", requestTimeoutMillis)
                          .add("maxRequestLength", maxRequestLength)
                          .add("verboseResponses", verboseResponses)
                          .add("accessLogWriter", accessLogWriter)
                          .add("blockingTaskExecutor", blockingTaskExecutor)
                          .add("successFunction", successFunction)
                          .add("multipartUploadsLocation", multipartUploadsLocation)
                          .add("shutdownSupports", shutdownSupports)
                          .add("defaultHeaders", defaultHeaders)
                          .add("serviceErrorHandler", serviceErrorHandler)
                          .toString();
    }
}
