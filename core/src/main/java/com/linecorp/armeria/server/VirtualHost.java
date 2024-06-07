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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.google.common.base.Ascii;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;

/**
 * A <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
 * A {@link VirtualHost} contains the following information:
 * <ul>
 *   <li>the hostname pattern, as defined in
 *       <a href="https://datatracker.ietf.org/doc/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a></li>
 *   <li>{@link SslContext} if TLS is enabled</li>
 *   <li>the list of available {@link HttpService}s and their {@link Route}s</li>
 * </ul>
 *
 * @see VirtualHostBuilder
 */
public final class VirtualHost {

    static final Pattern HOSTNAME_WITH_NO_PORT_PATTERN = Pattern.compile(
            "^(?:[-_a-zA-Z0-9]|[-_a-zA-Z0-9][-_.a-zA-Z0-9]*[-_a-zA-Z0-9])$");

    /**
     * Initialized later via {@link #setServerConfig(ServerConfig)}.
     */
    @Nullable
    private ServerConfig serverConfig;

    private final String originalDefaultHostname;
    private final String originalHostnamePattern;
    private final String defaultHostname;
    private final String hostnamePattern;
    private final int port;
    @Nullable
    private final SslContext sslContext;
    @Nullable
    private final TlsEngineType tlsEngineType;
    private final Router<ServiceConfig> router;
    private final List<ServiceConfig> serviceConfigs;
    private final ServiceConfig fallbackServiceConfig;

    private final Logger accessLogger;

    private final ServiceNaming defaultServiceNaming;
    private final String defaultLogName;
    private final long requestTimeoutMillis;
    private final long maxRequestLength;
    private final boolean verboseResponses;
    private final AccessLogWriter accessLogWriter;
    private final BlockingTaskExecutor blockingTaskExecutor;
    private final long requestAutoAbortDelayMillis;
    private final SuccessFunction successFunction;
    private final Path multipartUploadsLocation;
    private final MultipartRemovalStrategy multipartRemovalStrategy;
    private final EventLoopGroup serviceWorkerGroup;
    private final List<ShutdownSupport> shutdownSupports;
    private final Function<RoutingContext, RequestId> requestIdGenerator;

    VirtualHost(String defaultHostname, String hostnamePattern, int port,
                @Nullable SslContext sslContext,
                @Nullable TlsEngineType tlsEngineType,
                Iterable<ServiceConfig> serviceConfigs,
                ServiceConfig fallbackServiceConfig,
                RejectedRouteHandler rejectionHandler,
                Function<? super VirtualHost, ? extends Logger> accessLoggerMapper,
                ServiceNaming defaultServiceNaming,
                String defaultLogName,
                long requestTimeoutMillis,
                long maxRequestLength, boolean verboseResponses,
                AccessLogWriter accessLogWriter,
                BlockingTaskExecutor blockingTaskExecutor,
                long requestAutoAbortDelayMillis,
                SuccessFunction successFunction,
                Path multipartUploadsLocation,
                MultipartRemovalStrategy multipartRemovalStrategy,
                EventLoopGroup serviceWorkerGroup,
                List<ShutdownSupport> shutdownSupports,
                Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        originalDefaultHostname = defaultHostname;
        originalHostnamePattern = hostnamePattern;
        if (port > 0) {
            this.defaultHostname = defaultHostname + ':' + port;
            this.hostnamePattern = hostnamePattern + ':' + port;
        } else {
            this.defaultHostname = defaultHostname;
            this.hostnamePattern = hostnamePattern;
        }
        this.port = port;
        this.sslContext = sslContext;
        this.tlsEngineType = tlsEngineType;
        this.defaultServiceNaming = defaultServiceNaming;
        this.defaultLogName = defaultLogName;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.maxRequestLength = maxRequestLength;
        this.verboseResponses = verboseResponses;
        this.accessLogWriter = accessLogWriter;
        this.blockingTaskExecutor = blockingTaskExecutor;
        this.requestAutoAbortDelayMillis = requestAutoAbortDelayMillis;
        this.successFunction = successFunction;
        this.multipartUploadsLocation = multipartUploadsLocation;
        this.multipartRemovalStrategy = multipartRemovalStrategy;
        this.serviceWorkerGroup = serviceWorkerGroup;
        this.shutdownSupports = shutdownSupports;
        @SuppressWarnings("unchecked")
        final Function<RoutingContext, RequestId> castRequestIdGenerator =
                (Function<RoutingContext, RequestId>) requireNonNull(requestIdGenerator, "requestIdGenerator");
        this.requestIdGenerator = castRequestIdGenerator;

        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(fallbackServiceConfig, "fallbackServiceConfig");
        this.serviceConfigs = Streams.stream(serviceConfigs)
                                     .map(sc -> sc.withVirtualHost(this))
                                     .collect(toImmutableList());
        this.fallbackServiceConfig = fallbackServiceConfig.withVirtualHost(this);

        router = Routers.ofVirtualHost(this, this.serviceConfigs, rejectionHandler);

        accessLogger = accessLoggerMapper.apply(this);
        checkState(accessLogger != null,
                   "accessLoggerMapper.apply() has returned null for virtual host: %s.", hostnamePattern);
    }

    VirtualHost withNewSslContext(SslContext sslContext) {
        return new VirtualHost(originalDefaultHostname, originalHostnamePattern, port, sslContext,
                               tlsEngineType, serviceConfigs, fallbackServiceConfig,
                               RejectedRouteHandler.DISABLED, host -> accessLogger, defaultServiceNaming,
                               defaultLogName, requestTimeoutMillis, maxRequestLength, verboseResponses,
                               accessLogWriter, blockingTaskExecutor, requestAutoAbortDelayMillis,
                               successFunction, multipartUploadsLocation, multipartRemovalStrategy,
                               serviceWorkerGroup,
                               shutdownSupports, requestIdGenerator);
    }

    /**
     * IDNA ASCII conversion, case normalization and validation.
     */
    static String normalizeDefaultHostname(String defaultHostname) {
        requireNonNull(defaultHostname, "defaultHostname");
        if (needsNormalization(defaultHostname)) {
            defaultHostname = IDN.toASCII(defaultHostname, IDN.ALLOW_UNASSIGNED);
        }

        if (!HOSTNAME_WITH_NO_PORT_PATTERN.matcher(defaultHostname).matches()) {
            throw new IllegalArgumentException("defaultHostname: " + defaultHostname);
        }

        return Ascii.toLowerCase(defaultHostname);
    }

    /**
     * IDNA ASCII conversion, case normalization and validation.
     */
    static String normalizeHostnamePattern(String hostnamePattern) {
        requireNonNull(hostnamePattern, "hostnamePattern");
        if (needsNormalization(hostnamePattern)) {
            hostnamePattern = IDN.toASCII(hostnamePattern, IDN.ALLOW_UNASSIGNED);
        }

        final String withoutWildCard = hostnamePattern.startsWith("*.") ? hostnamePattern.substring(2)
                                                                        : hostnamePattern;
        if (!"*".equals(hostnamePattern) && !HOSTNAME_WITH_NO_PORT_PATTERN.matcher(withoutWildCard).matches()) {
            throw new IllegalArgumentException("hostnamePattern: " + hostnamePattern);
        }

        return Ascii.toLowerCase(hostnamePattern);
    }

    static void validateHostnamePattern(String hostnamePattern) {
        final boolean validHostnamePattern;
        if (hostnamePattern.charAt(0) == '*') {
            validHostnamePattern =
                    hostnamePattern.length() >= 3 &&
                    hostnamePattern.charAt(1) == '.' &&
                    HOSTNAME_WITH_NO_PORT_PATTERN.matcher(hostnamePattern.substring(2)).matches();
        } else {
            validHostnamePattern = HOSTNAME_WITH_NO_PORT_PATTERN.matcher(hostnamePattern).matches();
        }

        checkArgument(validHostnamePattern,
                      "hostnamePattern: %s (expected: *.<hostname> or <hostname>)", hostnamePattern);
    }

    /**
     * Ensure that 'hostnamePattern' matches 'defaultHostname'.
     */
    static void ensureHostnamePatternMatchesDefaultHostname(String hostnamePattern, String defaultHostname) {
        if ("*".equals(hostnamePattern)) {
            return;
        }

        // Pretty convoluted way to validate but it's done only once and
        // we don't need to duplicate the pattern matching logic.
        final Mapping<String, Boolean> mapping =
                new DomainMappingBuilder<>(Boolean.FALSE).add(hostnamePattern, Boolean.TRUE).build();

        if (!mapping.map(defaultHostname)) {
            throw new IllegalArgumentException(
                    "defaultHostname: " + defaultHostname +
                    " (must be matched by hostnamePattern: " + hostnamePattern + ')');
        }
    }

    private static boolean needsNormalization(String hostnamePattern) {
        final int length = hostnamePattern.length();
        for (int i = 0; i < length; i++) {
            final int c = hostnamePattern.charAt(i);
            if (c > 0x7F) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the {@code originalHostnamePattern} that does not include the {@link #port()} in the pattern.
     * For example, if a port number is 8080 and a hostname is {@code foo.com},
     * the {@link #hostnamePattern()} will be {@code "foo.com:8080"} and
     * the {@link #originalHostnamePattern()} will be {@code "foo.com"}.
     */
    String originalHostnamePattern() {
        return originalHostnamePattern;
    }

    /**
     * Returns the {@link Server} where this {@link VirtualHost} belongs to.
     */
    public Server server() {
        if (serverConfig == null) {
            throw new IllegalStateException("server is not configured yet.");
        }
        return serverConfig.server();
    }

    void setServerConfig(ServerConfig serverConfig) {
        if (this.serverConfig != null) {
            throw new IllegalStateException("VirtualHost cannot be added to more than one Server.");
        }

        this.serverConfig = requireNonNull(serverConfig, "serverConfig");

        final MeterRegistry registry = serverConfig.meterRegistry();
        final MeterIdPrefix idPrefix =
                new MeterIdPrefix("armeria.server.router.virtual.host.cache",
                                  "hostname.pattern", hostnamePattern);
        router.registerMetrics(registry, idPrefix);
    }

    /**
     * Returns the default hostname of this virtual host.
     */
    public String defaultHostname() {
        return defaultHostname;
    }

    /**
     * Returns the hostname pattern of this virtual host, as defined in
     * <a href="https://datatracker.ietf.org/doc/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a>.
     */
    public String hostnamePattern() {
        return hostnamePattern;
    }

    /**
     * Returns the port of this virtual host.
     * {@code -1} means that no port number is specified.
     */
    public int port() {
        return port;
    }

    /**
     * Returns the {@link SslContext} of this virtual host.
     */
    @Nullable
    public SslContext sslContext() {
        return sslContext;
    }

    /**
     * Returns the {@link TlsEngineType} of this virtual host.
     */
    @Nullable
    @UnstableApi
    public TlsEngineType tlsEngineType() {
        return tlsEngineType;
    }

    /**
     * Returns the information about the {@link HttpService}s bound to this virtual host.
     */
    public List<ServiceConfig> serviceConfigs() {
        return serviceConfigs;
    }

    /**
     * Returns the {@link Logger} which is used for writing access logs of this virtual host.
     */
    public Logger accessLogger() {
        return accessLogger;
    }

    /**
     * Returns a default naming rule for the name of services.
     *
     * @see ServiceConfig#defaultServiceNaming()
     */
    public ServiceNaming defaultServiceNaming() {
        return defaultServiceNaming;
    }

    /**
     * Returns the default value of the {@link RequestLog#name()} property which is used when no name was set
     * via {@link RequestLogBuilder#name(String, String)}.
     */
    public String defaultLogName() {
        return defaultLogName;
    }

    /**
     * Returns the timeout of a request.
     *
     * @see ServiceConfig#requestTimeoutMillis()
     */
    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /**
     * Returns the maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request.
     *
     * @see ServiceConfig#maxRequestLength()
     */
    public long maxRequestLength() {
        return maxRequestLength;
    }

    /**
     * Returns whether the verbose response mode is enabled. When enabled, the server responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the server responses will not expose such server-side details to the client.
     *
     * @see ServiceConfig#verboseResponses()
     */
    public boolean verboseResponses() {
        return verboseResponses;
    }

    /**
     * Returns the access log writer.
     *
     * @see ServiceConfig#accessLogWriter()
     */
    public AccessLogWriter accessLogWriter() {
        return accessLogWriter;
    }

    /**
     * Tells whether the {@link AccessLogWriter} is shut down when the {@link Server} stops.
     *
     * @deprecated This method is not used anymore. The {@link AccessLogWriter} is shut down if
     *             the {@code shutdownOnStop} of
     *             {@link VirtualHostBuilder#accessLogWriter(AccessLogWriter, boolean)}
     *             is set to {@code true}.
     */
    @Deprecated
    public boolean shutdownAccessLogWriterOnStop() {
        return false;
    }

    /**
     * Returns the blocking task executor.
     *
     * @see ServiceConfig#blockingTaskExecutor()
     */
    public BlockingTaskExecutor blockingTaskExecutor() {
        return blockingTaskExecutor;
    }

    /**
     * Returns whether the blocking task {@link Executor} is shut down when the {@link Server} stops.
     *
     * @deprecated This method is not used anymore. The {@code blockingTaskExecutor} is shut down if
     *             the {@code shutdownOnStop} of
     *             {@link VirtualHostBuilder#blockingTaskExecutor(ScheduledExecutorService, boolean)}
     *             is set to {@code true}.
     */
    @Deprecated
    public boolean shutdownBlockingTaskExecutorOnStop() {
        return false;
    }

    /**
     * Returns the service {@link EventLoopGroup}.
     *
     * @see ServiceConfig#serviceWorkerGroup()
     */
    @UnstableApi
    public EventLoopGroup serviceWorkerGroup() {
        return serviceWorkerGroup;
    }

    /**
     * Returns the {@link SuccessFunction} that determines whether a request was
     * handled successfully or not.
     */
    public SuccessFunction successFunction() {
        return successFunction;
    }

    /**
     * Returns the {@link Function} that generates a {@link RequestId}.
     */
    public Function<RoutingContext, RequestId> requestIdGenerator() {
        return requestIdGenerator;
    }

    /**
     * Finds the {@link HttpService} whose {@link Router} matches the {@link RoutingContext}.
     *
     * @param routingCtx a context to find the {@link HttpService}.
     *
     * @return the {@link ServiceConfig} wrapped by a {@link Routed} if there's a match.
     *         {@link Routed#empty()} if there's no match.
     */
    public Routed<ServiceConfig> findServiceConfig(RoutingContext routingCtx) {
        return findServiceConfig(routingCtx, false);
    }

    /**
     * Finds the {@link HttpService} whose {@link Router} matches the {@link RoutingContext}.
     *
     * @param routingCtx a context to find the {@link HttpService}.
     * @param useFallbackService whether to use the fallback {@link HttpService} when there is no match.
     *                           If {@code true}, the returned {@link Routed} will always be present.
     *
     * @return the {@link ServiceConfig} wrapped by a {@link Routed} if there's a match.
     *         The fallback {@link ServiceConfig} wrapped by {@link Routed} if there's no match and
     *         {@code useFallbackService} is {@code true}.
     *         {@link Routed#empty()} if there's no match and {@code useFallbackService} is {@code false}.
     */
    public Routed<ServiceConfig> findServiceConfig(RoutingContext routingCtx, boolean useFallbackService) {
        final Routed<ServiceConfig> routed = router.find(requireNonNull(routingCtx, "routingCtx"));
        switch (routed.routingResultType()) {
            case MATCHED:
                maybeSetRoutingResult(routingCtx, routed);
                return routed;
            case NOT_MATCHED:
                if (routingCtx.method() == HttpMethod.HEAD) {
                    return findServiceConfig(routingCtx.withMethod(HttpMethod.GET), useFallbackService);
                }

                if (!useFallbackService) {
                    maybeSetRoutingResult(routingCtx, routed);
                    return routed;
                }
                break;
            case CORS_PREFLIGHT:
                assert routingCtx.status() == RoutingStatus.CORS_PREFLIGHT;
                if (routed.value().handlesCorsPreflight()) {
                    maybeSetRoutingResult(routingCtx, routed);
                    // CorsService will handle the preflight request
                    // even if the service does not handle an OPTIONS method.
                    return routed;
                } else {
                    // `handlesCorsPreflight()` is false if `CorsService` is set as a route decorator.
                    // However, this is not a problem because the CorsService is chosen and applied by
                    // `InitialDispatcherService` regardless of the target service.
                }
                break;
            default:
                // Never reaches here.
                throw new Error();
        }

        // Note that we did not implement this fallback mechanism inside a Router implementation like
        // CompositeRouter because we wanted to avoid caching non-existent mappings.
        final Routed<ServiceConfig> fallbackRoute =
                Routed.of(fallbackServiceConfig.route(),
                          RoutingResult.builder()
                                       .path(routingCtx.path())
                                       .query(routingCtx.query())
                                       .build(),
                          fallbackServiceConfig);
        maybeSetRoutingResult(routingCtx, fallbackRoute);
        return fallbackRoute;
    }

    private static void maybeSetRoutingResult(RoutingContext routingContext, Routed<ServiceConfig> routed) {
        if (!routingContext.hasResult()) {
            routingContext.setResult(routed);
        }
    }

    ServiceConfig fallbackServiceConfig() {
        return fallbackServiceConfig;
    }

    List<ShutdownSupport> shutdownSupports() {
        return shutdownSupports;
    }

    /**
     * Returns the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     */
    public long requestAutoAbortDelayMillis() {
        return requestAutoAbortDelayMillis;
    }

    /**
     * Returns the {@link Path} that is used to store the files uploaded
     * through a {@code multipart/form-data} request.
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

    VirtualHost decorate(@Nullable Function<? super HttpService, ? extends HttpService> decorator) {
        if (decorator == null) {
            return this;
        }

        final List<ServiceConfig> serviceConfigs =
                this.serviceConfigs.stream()
                                   .map(cfg -> cfg.withDecoratedService(decorator))
                                   .collect(Collectors.toList());

        final ServiceConfig fallbackServiceConfig =
                this.fallbackServiceConfig.withDecoratedService(decorator);

        return new VirtualHost(originalDefaultHostname, originalHostnamePattern, port, sslContext,
                               tlsEngineType, serviceConfigs, fallbackServiceConfig,
                               RejectedRouteHandler.DISABLED, host -> accessLogger, defaultServiceNaming,
                               defaultLogName, requestTimeoutMillis, maxRequestLength, verboseResponses,
                               accessLogWriter, blockingTaskExecutor, requestAutoAbortDelayMillis,
                               successFunction, multipartUploadsLocation, multipartRemovalStrategy,
                               serviceWorkerGroup, shutdownSupports, requestIdGenerator);
    }

    @Override
    public String toString() {
        return toString(true);
    }

    private String toString(boolean withTypeName) {
        final StringBuilder buf = new StringBuilder();
        if (withTypeName) {
            buf.append(getClass().getSimpleName());
        }

        buf.append('(');
        buf.append(defaultHostname());
        buf.append('/');
        buf.append(hostnamePattern());
        buf.append(", ssl: ");
        buf.append(sslContext() != null);
        buf.append(", services: ");
        buf.append(serviceConfigs);
        buf.append(", accessLogger: ");
        buf.append(accessLogger());
        buf.append(", defaultServiceNaming: ");
        buf.append(defaultServiceNaming());
        buf.append(", requestTimeoutMillis: ");
        buf.append(requestTimeoutMillis());
        buf.append(", maxRequestLength: ");
        buf.append(maxRequestLength());
        buf.append(", verboseResponses: ");
        buf.append(verboseResponses());
        buf.append(", accessLogWriter: ");
        buf.append(accessLogWriter());
        buf.append(", blockingTaskExecutor: ");
        buf.append(blockingTaskExecutor());
        buf.append(", requestAutoAbortDelayMillis: ");
        buf.append(requestAutoAbortDelayMillis());
        buf.append(", multipartUploadsLocation: ");
        buf.append(multipartUploadsLocation());
        buf.append(')');
        return buf.toString();
    }

    String toStringWithoutTypeName() {
        return toString(false);
    }
}
