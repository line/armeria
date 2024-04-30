/*
 * Copyright 2015 LINE Corporation
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.channel.EventLoopGroup;

/**
 * An {@link HttpService} configuration.
 *
 * @see ServerConfig#serviceConfigs()
 * @see VirtualHost#serviceConfigs()
 */
public final class ServiceConfig {

    @Nullable
    private final VirtualHost virtualHost;

    private final Route route;
    private final Route mappedRoute;
    private final HttpService service;
    @Nullable
    private final String defaultServiceName;
    private final ServiceNaming defaultServiceNaming;
    @Nullable
    private final String defaultLogName;

    private final long requestTimeoutMillis;
    private final long maxRequestLength;
    private final boolean verboseResponses;

    private final AccessLogWriter accessLogWriter;
    private final Set<TransientServiceOption> transientServiceOptions;
    private final boolean handlesCorsPreflight;
    private final SuccessFunction successFunction;

    private final BlockingTaskExecutor blockingTaskExecutor;

    private final long requestAutoAbortDelayMillis;
    private final Path multipartUploadsLocation;
    private final MultipartRemovalStrategy multipartRemovalStrategy;
    private final EventLoopGroup serviceWorkerGroup;

    private final List<ShutdownSupport> shutdownSupports;
    private final HttpHeaders defaultHeaders;
    private final Function<RoutingContext, RequestId> requestIdGenerator;
    private final ServiceErrorHandler serviceErrorHandler;
    private final Supplier<AutoCloseable> contextHook;

    /**
     * Creates a new instance.
     */
    ServiceConfig(Route route, Route mappedRoute, HttpService service, @Nullable String defaultLogName,
                  @Nullable String defaultServiceName, ServiceNaming defaultServiceNaming,
                  long requestTimeoutMillis, long maxRequestLength,
                  boolean verboseResponses, AccessLogWriter accessLogWriter,
                  BlockingTaskExecutor blockingTaskExecutor,
                  SuccessFunction successFunction, long requestAutoAbortDelayMillis,
                  Path multipartUploadsLocation, MultipartRemovalStrategy multipartRemovalStrategy,
                  EventLoopGroup serviceWorkerGroup,
                  List<ShutdownSupport> shutdownSupports,
                  HttpHeaders defaultHeaders,
                  Function<? super RoutingContext, ? extends RequestId> requestIdGenerator,
                  ServiceErrorHandler serviceErrorHandler, Supplier<? extends AutoCloseable> contextHook) {
        this(null, route, mappedRoute, service, defaultLogName, defaultServiceName, defaultServiceNaming,
             requestTimeoutMillis, maxRequestLength, verboseResponses, accessLogWriter,
             extractTransientServiceOptions(service),
             blockingTaskExecutor, successFunction, requestAutoAbortDelayMillis,
             multipartUploadsLocation, multipartRemovalStrategy, serviceWorkerGroup, shutdownSupports,
             defaultHeaders, requestIdGenerator, serviceErrorHandler, contextHook);
    }

    /**
     * Creates a new instance.
     */
    private ServiceConfig(@Nullable VirtualHost virtualHost, Route route,
                          Route mappedRoute, HttpService service,
                          @Nullable String defaultLogName, @Nullable String defaultServiceName,
                          ServiceNaming defaultServiceNaming, long requestTimeoutMillis, long maxRequestLength,
                          boolean verboseResponses, AccessLogWriter accessLogWriter,
                          Set<TransientServiceOption> transientServiceOptions,
                          BlockingTaskExecutor blockingTaskExecutor,
                          SuccessFunction successFunction,
                          long requestAutoAbortDelayMillis,
                          Path multipartUploadsLocation, MultipartRemovalStrategy multipartRemovalStrategy,
                          EventLoopGroup serviceWorkerGroup,
                          List<ShutdownSupport> shutdownSupports, HttpHeaders defaultHeaders,
                          Function<? super RoutingContext, ? extends RequestId> requestIdGenerator,
                          ServiceErrorHandler serviceErrorHandler,
                          Supplier<? extends AutoCloseable> contextHook) {
        this.virtualHost = virtualHost;
        this.route = requireNonNull(route, "route");
        this.mappedRoute = requireNonNull(mappedRoute, "mappedRoute");
        this.service = requireNonNull(service, "service");
        this.defaultLogName = defaultLogName;
        this.defaultServiceName = defaultServiceName;
        this.defaultServiceNaming = requireNonNull(defaultServiceNaming, "defaultServiceNaming");
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        this.verboseResponses = verboseResponses;
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        this.transientServiceOptions = requireNonNull(transientServiceOptions, "transientServiceOptions");
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        this.successFunction = requireNonNull(successFunction, "successFunction");
        this.requestAutoAbortDelayMillis = requestAutoAbortDelayMillis;
        this.multipartUploadsLocation = requireNonNull(multipartUploadsLocation, "multipartUploadsLocation");
        this.multipartRemovalStrategy = requireNonNull(multipartRemovalStrategy, "multipartRemovalStrategy");
        this.serviceWorkerGroup = requireNonNull(serviceWorkerGroup, "serviceWorkerGroup");
        this.shutdownSupports = ImmutableList.copyOf(requireNonNull(shutdownSupports, "shutdownSupports"));
        this.defaultHeaders = defaultHeaders;
        @SuppressWarnings("unchecked")
        final Function<RoutingContext, RequestId> castRequestIdGenerator =
                (Function<RoutingContext, RequestId>) requireNonNull(requestIdGenerator, "requestIdGenerator");
        this.requestIdGenerator = castRequestIdGenerator;
        this.serviceErrorHandler = requireNonNull(serviceErrorHandler, "serviceErrorHandler");
        //noinspection unchecked
        this.contextHook = (Supplier<AutoCloseable>) requireNonNull(contextHook, "contextHook");

        handlesCorsPreflight = service.as(CorsService.class) != null;
    }

    private static Set<TransientServiceOption> extractTransientServiceOptions(HttpService service) {
        @SuppressWarnings("rawtypes")
        final TransientService transientService = service.as(TransientService.class);
        if (transientService == null) {
            return TransientServiceOption.allOf();
        }
        @SuppressWarnings("unchecked")
        final Set<TransientServiceOption> transientServiceOptions =
                (Set<TransientServiceOption>) transientService.transientServiceOptions();
        return transientServiceOptions;
    }

    static long validateRequestTimeoutMillis(long requestTimeoutMillis) {
        if (requestTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMillis: " + requestTimeoutMillis + " (expected: >= 0)");
        }
        return requestTimeoutMillis;
    }

    static long validateMaxRequestLength(long maxRequestLength) {
        if (maxRequestLength < 0) {
            throw new IllegalArgumentException("maxRequestLength: " + maxRequestLength + " (expected: >= 0)");
        }
        return maxRequestLength;
    }

    ServiceConfig withVirtualHost(VirtualHost virtualHost) {
        requireNonNull(virtualHost, "virtualHost");
        return new ServiceConfig(virtualHost, route, mappedRoute, service, defaultLogName, defaultServiceName,
                                 defaultServiceNaming, requestTimeoutMillis, maxRequestLength, verboseResponses,
                                 accessLogWriter, transientServiceOptions,
                                 blockingTaskExecutor, successFunction, requestAutoAbortDelayMillis,
                                 multipartUploadsLocation, multipartRemovalStrategy, serviceWorkerGroup,
                                 shutdownSupports, defaultHeaders,
                                 requestIdGenerator, serviceErrorHandler, contextHook);
    }

    ServiceConfig withDecoratedService(Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        return new ServiceConfig(virtualHost, route, mappedRoute, service.decorate(decorator), defaultLogName,
                                 defaultServiceName, defaultServiceNaming, requestTimeoutMillis,
                                 maxRequestLength, verboseResponses,
                                 accessLogWriter, transientServiceOptions,
                                 blockingTaskExecutor, successFunction, requestAutoAbortDelayMillis,
                                 multipartUploadsLocation, multipartRemovalStrategy, serviceWorkerGroup,
                                 shutdownSupports, defaultHeaders,
                                 requestIdGenerator, serviceErrorHandler, contextHook);
    }

    ServiceConfig withRoute(Route route) {
        requireNonNull(route, "route");
        return new ServiceConfig(virtualHost, route, mappedRoute, service, defaultLogName, defaultServiceName,
                                 defaultServiceNaming, requestTimeoutMillis, maxRequestLength, verboseResponses,
                                 accessLogWriter, transientServiceOptions,
                                 blockingTaskExecutor, successFunction, requestAutoAbortDelayMillis,
                                 multipartUploadsLocation, multipartRemovalStrategy, serviceWorkerGroup,
                                 shutdownSupports, defaultHeaders,
                                 requestIdGenerator, serviceErrorHandler, contextHook);
    }

    /**
     * Returns the {@link VirtualHost} the {@link #service()} belongs to.
     */
    public VirtualHost virtualHost() {
        if (virtualHost == null) {
            throw new IllegalStateException("Server has not been configured yet.");
        }
        return virtualHost;
    }

    /**
     * Returns the {@link Server} the {@link #service()} belongs to.
     */
    public Server server() {
        return virtualHost().server();
    }

    /**
     * Returns the {@link Route} of the {@link #service()}.
     */
    public Route route() {
        return route;
    }

    /**
     * Returns the {@link Route} whose prefix is removed when an {@link HttpServiceWithRoutes} is added
     * via {@link ServerBuilder#serviceUnder(String, HttpService)}.
     * For example, in the following code, the path of the {@link #mappedRoute()} will be ({@code "/bar"})
     * whereas the path of the {@link #route()} will be ({@code "/foo/bar"}):
     * <pre>{@code
     * > HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }
     * >
     * >     @Override
     * >     public Set<Route> routes() {
     * >         return Set.of(Route.builder().path("/bar").build());
     * >     }
     * > };
     * >
     * > Server.builder()
     * >       .serviceUnder("/foo", serviceWithRoutes)
     * >       .build();
     * }</pre>
     * If the service is not an {@link HttpServiceWithRoutes}, the {@link Route} is the same as
     * {@link #route()}.
     */
    public Route mappedRoute() {
        return mappedRoute;
    }

    /**
     * Returns the {@link HttpService}.
     */
    public HttpService service() {
        return service;
    }

    /**
     * Returns the default value of the {@link RequestLog#serviceName()} property which is used when
     * no service name was set via {@link RequestLogBuilder#name(String, String)}.
     * If {@code null}, one of the following values will be used instead:
     * <ul>
     *   <li>gRPC - a service name (e.g, {@code com.foo.GrpcService})</li>
     *   <li>Thrift - a service type (e.g, {@code com.foo.ThriftService$AsyncIface} or
     *       {@code com.foo.ThriftService$Iface})</li>
     *   <li>{@link HttpService} and annotated service - an innermost class name</li>
     * </ul>
     *
     * @deprecated Use {@link #defaultServiceNaming()} instead.
     */
    @Nullable
    @Deprecated
    public String defaultServiceName() {
        return defaultServiceName;
    }

    /**
     * Returns a default naming rule for the name of services.
     *
     * @see VirtualHost#defaultServiceNaming()
     */
    public ServiceNaming defaultServiceNaming() {
        return defaultServiceNaming;
    }

    /**
     * Returns the default value of the {@link RequestLog#name()} property which is used when no name was set
     * via {@link RequestLogBuilder#name(String, String)}.
     * If {@code null}, one of the following values will be used instead:
     * <ul>
     *   <li>gRPC - A capitalized method name defined in {@code io.grpc.MethodDescriptor}
     *       (e.g, {@code GetItems})</li>
     *   <li>Thrift and annotated service - a method name (e.g, {@code getItems})</li>
     *   <li>{@link HttpService} - an HTTP method name</li>
     * </ul>
     */
    @Nullable
    public String defaultLogName() {
        return defaultLogName;
    }

    /**
     * Returns the timeout of a request.
     *
     * @see VirtualHost#requestTimeoutMillis()
     */
    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /**
     * Returns the maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request.
     *
     * @see VirtualHost#maxRequestLength()
     */
    public long maxRequestLength() {
        return maxRequestLength;
    }

    /**
     * Returns whether the verbose response mode is enabled. When enabled, the service response will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service response will not expose such server-side details to the client.
     *
     * @see VirtualHost#verboseResponses()
     */
    public boolean verboseResponses() {
        return verboseResponses;
    }

    /**
     * Returns the access log writer.
     *
     * @see VirtualHost#accessLogWriter()
     */
    public AccessLogWriter accessLogWriter() {
        return accessLogWriter;
    }

    /**
     * Tells whether the {@link AccessLogWriter} is shut down when the {@link Server} stops.
     *
     * @deprecated This method is not used anymore. The {@link AccessLogWriter} is shut down if
     *             the {@code shutdownOnStop} of
     *             {@link ServiceBindingBuilder#accessLogWriter(AccessLogWriter, boolean)}
     *             is set to {@code true}.
     */
    @Deprecated
    public boolean shutdownAccessLogWriterOnStop() {
        return false;
    }

    /**
     * Returns the {@link Set} of {@link TransientServiceOption}s that are enabled for the {@link #service()}.
     * This method always returns {@link TransientServiceOption#allOf()} for a non-{@link TransientService}
     * because only a {@link TransientService} can opt in and out using
     * {@link TransientServiceBuilder#transientServiceOptions(TransientServiceOption...)}.
     */
    public Set<TransientServiceOption> transientServiceOptions() {
        return transientServiceOptions;
    }

    /**
     * Returns {@code true} if the service has {@link CorsDecorator} in the decorator chain.
     */
    boolean handlesCorsPreflight() {
        return handlesCorsPreflight;
    }

    /**
     * Returns the {@link BlockingTaskExecutor} dedicated to the execution of blocking tasks or invocations
     * within this route.
     * Note that the {@link BlockingTaskExecutor} returned by this method does not set the
     * {@link ServiceRequestContext} when executing a submitted task.
     * Use {@link ServiceRequestContext#blockingTaskExecutor()} if possible.
     */
    public BlockingTaskExecutor blockingTaskExecutor() {
        return blockingTaskExecutor;
    }

    /**
     * Returns whether the blocking task {@link Executor} is shut down when the {@link Server} stops.
     *
     * @deprecated This method is not used anymore. The {@code blockingTaskExecutor} is shut down if
     *             the {@code shutdownOnStop} of
     *             {@link ServiceBindingBuilder#blockingTaskExecutor(ScheduledExecutorService, boolean)}
     *             is set to {@code true}.
     */
    @Deprecated
    public boolean shutdownBlockingTaskExecutorOnStop() {
        return false;
    }

    /**
     * Returns the {@link SuccessFunction} that determines whether a request was
     * handled successfully or not.
     */
    public SuccessFunction successFunction() {
        return successFunction;
    }

    /**
     * Returns the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     */
    public long requestAutoAbortDelayMillis() {
        return requestAutoAbortDelayMillis;
    }

    /**
     * Returns the {@link Path} that is used to store uploaded file through multipart/form-data.
     */
    public Path multipartUploadsLocation() {
        return multipartUploadsLocation;
    }

    /**
     * Returns the {@link MultipartRemovalStrategy} that specifies when to remove the temporary files created
     * for multipart requests.
     */
    @UnstableApi
    public MultipartRemovalStrategy multipartRemovalStrategy() {
        return multipartRemovalStrategy;
    }

    /**
     * Returns the {@link Function} that generates a {@link RequestId}.
     */
    public Function<RoutingContext, RequestId> requestIdGenerator() {
        return requestIdGenerator;
    }

    ServiceErrorHandler errorHandler() {
        return serviceErrorHandler;
    }

    /**
     * Returns the {@link Supplier} which provides an {@link AutoCloseable} and will be called whenever this
     * {@link RequestContext} is popped from the {@link RequestContextStorage}.
     */
    @UnstableApi
    public Supplier<AutoCloseable> contextHook() {
        return contextHook;
    }

    List<ShutdownSupport> shutdownSupports() {
        return shutdownSupports;
    }

    /**
     * Returns the {@link EventLoopGroup} dedicated to the execution of services' methods.
     */
    @UnstableApi
    public EventLoopGroup serviceWorkerGroup() {
        return serviceWorkerGroup;
    }

    /**
     * Returns the default headers for an {@link HttpResponse} served by the {@link #service()}.
     */
    @UnstableApi
    public HttpHeaders defaultHeaders() {
        return defaultHeaders;
    }

    @Override
    public String toString() {
        final ToStringHelper toStringHelper = MoreObjects.toStringHelper(this).omitNullValues();
        if (virtualHost != null) {
            toStringHelper.add("hostnamePattern", virtualHost.hostnamePattern());
        }
        if (!defaultHeaders.isEmpty()) {
            toStringHelper.add("defaultHeaders", defaultHeaders);
        }

        return toStringHelper.add("route", route)
                             .add("service", service)
                             .add("defaultServiceNaming", defaultServiceNaming)
                             .add("defaultLogName", defaultLogName)
                             .add("requestTimeoutMillis", requestTimeoutMillis)
                             .add("maxRequestLength", maxRequestLength)
                             .add("verboseResponses", verboseResponses)
                             .add("accessLogWriter", accessLogWriter)
                             .add("blockingTaskExecutor", blockingTaskExecutor)
                             .add("successFunction", successFunction)
                             .add("requestAutoAbortDelayMillis", requestAutoAbortDelayMillis)
                             .add("multipartUploadsLocation", multipartUploadsLocation)
                             .add("serviceErrorHandler", serviceErrorHandler)
                             .add("shutdownSupports", shutdownSupports)
                             .toString();
    }
}
