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
import static com.linecorp.armeria.server.ServiceConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServiceConfig.validateRequestTimeoutMillis;
import static com.linecorp.armeria.server.VirtualHostBuilder.ensureNoPseudoHeader;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A default implementation of {@link ServiceConfigSetters} that stores service related settings
 * and provides a method {@link DefaultServiceConfigSetters#toServiceConfigBuilder(Route, HttpService)} to build
 * {@link ServiceConfigBuilder}.
 */
final class DefaultServiceConfigSetters implements ServiceConfigSetters {

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
    private Function<? super HttpService, ? extends HttpService> decorator;
    @Nullable
    private AccessLogWriter accessLogWriter;
    @Nullable
    private BlockingTaskExecutor blockingTaskExecutor;
    @Nullable
    private SuccessFunction successFunction;
    @Nullable
    private Long requestAutoAbortDelayMillis;
    @Nullable
    private Path multipartUploadsLocation;
    @Nullable
    private ServiceErrorHandler serviceErrorHandler;
    private final List<ShutdownSupport> shutdownSupports = new ArrayList<>();
    private final HttpHeadersBuilder defaultHeaders = HttpHeaders.builder();
    @Nullable
    private Function<? super RoutingContext, ? extends RequestId> requestIdGenerator;

    @Override
    public ServiceConfigSetters requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public ServiceConfigSetters requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public ServiceConfigSetters maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public ServiceConfigSetters verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    @Override
    public ServiceConfigSetters accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
    }

    @Override
    public ServiceConfigSetters accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        requireNonNull(accessLogWriter, "accessLogWriter");
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
    public ServiceConfigSetters decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(decorator);
        } else {
            this.decorator = decorator;
        }
        return this;
    }

    Function<? super HttpService, ? extends HttpService> decorator() {
        if (decorator == null) {
            return Function.identity();
        }
        return decorator;
    }

    @Override
    @SafeVarargs
    public final ServiceConfigSetters decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return decorators(ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    @Override
    public ServiceConfigSetters decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(decorators, "decorators");
        for (Function<? super HttpService, ? extends HttpService> decorator : decorators) {
            requireNonNull(decorator, "decorators contains null.");
            decorator(decorator);
        }
        return this;
    }

    @Override
    public ServiceConfigSetters defaultServiceName(String defaultServiceName) {
        requireNonNull(defaultServiceName, "defaultServiceName");
        this.defaultServiceName = defaultServiceName;
        defaultServiceNaming = ServiceNaming.of(defaultServiceName);
        return this;
    }

    @Override
    public ServiceConfigSetters defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceName = null;
        this.defaultServiceNaming = requireNonNull(defaultServiceNaming, "defaultServiceNaming");
        return this;
    }

    @Override
    public ServiceConfigSetters defaultLogName(String defaultLogName) {
        this.defaultLogName = requireNonNull(defaultLogName, "defaultLogName");
        return this;
    }

    @Override
    public ServiceConfigSetters blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                                     boolean shutdownOnStop) {
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        return blockingTaskExecutor(BlockingTaskExecutor.of(blockingTaskExecutor), shutdownOnStop);
    }

    @Override
    public ServiceConfigSetters blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                     boolean shutdownOnStop) {
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(blockingTaskExecutor));
        }
        return this;
    }

    @Override
    public ServiceConfigSetters blockingTaskExecutor(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.builder()
                                                                  .numThreads(numThreads)
                                                                  .build();
        return blockingTaskExecutor(executor, true);
    }

    @Override
    public ServiceConfigSetters successFunction(SuccessFunction successFunction) {
        this.successFunction = requireNonNull(successFunction, "successFunction");
        return this;
    }

    @Override
    public ServiceConfigSetters requestAutoAbortDelay(Duration delay) {
        return requestAutoAbortDelayMillis(requireNonNull(delay, "delay").toMillis());
    }

    @Override
    public ServiceConfigSetters requestAutoAbortDelayMillis(long delayMillis) {
        requestAutoAbortDelayMillis = delayMillis;
        return this;
    }

    @Override
    public ServiceConfigSetters multipartUploadsLocation(Path multipartUploadsLocation) {
        this.multipartUploadsLocation = requireNonNull(multipartUploadsLocation, "multipartUploadsLocation");
        return this;
    }

    @Override
    public ServiceConfigSetters requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        this.requestIdGenerator = requireNonNull(requestIdGenerator, "requestIdGenerator");
        return this;
    }

    @Override
    public ServiceConfigSetters addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureNoPseudoHeader(name);
        defaultHeaders.addObject(name, value);
        return this;
    }

    @Override
    public ServiceConfigSetters addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        requireNonNull(defaultHeaders, "defaultHeaders");
        ensureNoPseudoHeader(defaultHeaders);
        this.defaultHeaders.addObject(defaultHeaders);
        return this;
    }

    @Override
    public ServiceConfigSetters setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureNoPseudoHeader(name);
        defaultHeaders.setObject(name, value);
        return this;
    }

    @Override
    public ServiceConfigSetters setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        requireNonNull(defaultHeaders, "defaultHeaders");
        ensureNoPseudoHeader(defaultHeaders);
        this.defaultHeaders.setObject(defaultHeaders);
        return this;
    }

    @Override
    public ServiceConfigSetters errorHandler(ServiceErrorHandler serviceErrorHandler) {
        requireNonNull(serviceErrorHandler, "serviceErrorHandler");
        this.serviceErrorHandler = serviceErrorHandler;
        return this;
    }

    /**
     * Note: {@link ServiceConfigBuilder} built by this method is not decorated with the decorator function
     * which can be configured using {@link DefaultServiceConfigSetters#decorator()} because
     * {@link AnnotatedServiceBindingBuilder} needs exception handling decorators to be the last to handle
     * any exceptions thrown by the service and other decorators.
     */
    ServiceConfigBuilder toServiceConfigBuilder(Route route, HttpService service) {
        final ServiceConfigBuilder serviceConfigBuilder = new ServiceConfigBuilder(route, service);

        final AnnotatedService annotatedService;
        if (defaultServiceNaming == null || defaultLogName == null) {
            annotatedService = service.as(AnnotatedService.class);
        } else {
            annotatedService = null;
        }

        if (defaultServiceName != null) {
            serviceConfigBuilder.defaultServiceName(defaultServiceName);
        } else if (defaultServiceNaming != null) {
            serviceConfigBuilder.defaultServiceNaming(defaultServiceNaming);
        } else {
            // Set the default service name only when the service name is set using @ServiceName.
            // If it's not, the global defaultServiceNaming is used.
            if (annotatedService != null && annotatedService.serviceNameSetByAnnotation()) {
                serviceConfigBuilder.defaultServiceName(annotatedService.serviceName());
            }
        }

        if (defaultLogName != null) {
            serviceConfigBuilder.defaultLogName(defaultLogName);
        } else {
            if (annotatedService != null) {
                serviceConfigBuilder.defaultLogName(annotatedService.methodName());
            }
        }

        if (requestTimeoutMillis != null) {
            serviceConfigBuilder.requestTimeoutMillis(requestTimeoutMillis);
        }
        if (maxRequestLength != null) {
            serviceConfigBuilder.maxRequestLength(maxRequestLength);
        }
        if (verboseResponses != null) {
            serviceConfigBuilder.verboseResponses(verboseResponses);
        }
        if (accessLogWriter != null) {
            serviceConfigBuilder.accessLogWriter(accessLogWriter, false);
            // Set the accessLogWriter as false because it's shut down in ShutdownSupport.
        }
        if (blockingTaskExecutor != null) {
            serviceConfigBuilder.blockingTaskExecutor(blockingTaskExecutor, false);
            // Set the blockingTaskExecutor as false because it's shut down in ShutdownSupport.
        }
        if (successFunction != null) {
            serviceConfigBuilder.successFunction(successFunction);
        }
        if (requestAutoAbortDelayMillis != null) {
            serviceConfigBuilder.requestAutoAbortDelayMillis(requestAutoAbortDelayMillis);
        }
        if (multipartUploadsLocation != null) {
            serviceConfigBuilder.multipartUploadsLocation(multipartUploadsLocation);
        }
        if (requestIdGenerator != null) {
            serviceConfigBuilder.requestIdGenerator(requestIdGenerator);
        }
        serviceConfigBuilder.shutdownSupports(shutdownSupports);
        if (!defaultHeaders.isEmpty()) {
            serviceConfigBuilder.defaultHeaders(defaultHeaders.build());
        }
        if (serviceErrorHandler != null) {
            serviceConfigBuilder.errorHandler(serviceErrorHandler);
        }
        return serviceConfigBuilder;
    }
}
