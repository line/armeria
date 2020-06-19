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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.common.base.Ascii;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;

/**
 * A <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
 * A {@link VirtualHost} contains the following information:
 * <ul>
 *   <li>the hostname pattern, as defined in
 *       <a href="https://tools.ietf.org/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a></li>
 *   <li>{@link SslContext} if TLS is enabled</li>
 *   <li>the list of available {@link HttpService}s and their {@link Route}s</li>
 * </ul>
 *
 * @see VirtualHostBuilder
 */
public final class VirtualHost {

    static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?:[-_a-zA-Z0-9]|[-_a-zA-Z0-9][-_.a-zA-Z0-9]*[-_a-zA-Z0-9])$");

    /**
     * Initialized later via {@link #setServerConfig(ServerConfig)}.
     */
    @Nullable
    private ServerConfig serverConfig;

    private final String defaultHostname;
    private final String hostnamePattern;
    @Nullable
    private final SslContext sslContext;
    private final Router<ServiceConfig> router;
    private final List<ServiceConfig> serviceConfigs;
    private final ServiceConfig fallbackServiceConfig;

    private final Logger accessLogger;

    private final long requestTimeoutMillis;
    private final long maxRequestLength;
    private final boolean verboseResponses;
    private final AccessLogWriter accessLogWriter;
    private final boolean shutdownAccessLogWriterOnStop;

    VirtualHost(String defaultHostname, String hostnamePattern,
                @Nullable SslContext sslContext,
                Iterable<ServiceConfig> serviceConfigs,
                ServiceConfig fallbackServiceConfig,
                RejectedRouteHandler rejectionHandler,
                Function<? super VirtualHost, ? extends Logger> accessLoggerMapper,
                long requestTimeoutMillis,
                long maxRequestLength, boolean verboseResponses,
                AccessLogWriter accessLogWriter, boolean shutdownAccessLogWriterOnStop) {
        defaultHostname = normalizeDefaultHostname(defaultHostname);
        hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        ensureHostnamePatternMatchesDefaultHostname(hostnamePattern, defaultHostname);

        this.defaultHostname = defaultHostname;
        this.hostnamePattern = hostnamePattern;
        this.sslContext = sslContext;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.maxRequestLength = maxRequestLength;
        this.verboseResponses = verboseResponses;
        this.accessLogWriter = accessLogWriter;
        this.shutdownAccessLogWriterOnStop = shutdownAccessLogWriterOnStop;

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
        return new VirtualHost(defaultHostname(), hostnamePattern(), sslContext,
                               serviceConfigs(), fallbackServiceConfig, RejectedRouteHandler.DISABLED,
                               host -> accessLogger, requestTimeoutMillis(),
                               maxRequestLength(), verboseResponses(),
                               accessLogWriter(), shutdownAccessLogWriterOnStop());
    }

    /**
     * IDNA ASCII conversion, case normalization and validation.
     */
    static String normalizeDefaultHostname(String defaultHostname) {
        requireNonNull(defaultHostname, "defaultHostname");
        if (needsNormalization(defaultHostname)) {
            defaultHostname = IDN.toASCII(defaultHostname, IDN.ALLOW_UNASSIGNED);
        }

        if (!HOSTNAME_PATTERN.matcher(defaultHostname).matches()) {
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

        if (!"*".equals(hostnamePattern) &&
            !HOSTNAME_PATTERN.matcher(hostnamePattern.startsWith("*.") ? hostnamePattern.substring(2)
                                                                       : hostnamePattern).matches()) {
            throw new IllegalArgumentException("hostnamePattern: " + hostnamePattern);
        }

        return Ascii.toLowerCase(hostnamePattern);
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
                Flags.useLegacyMeterNames() ? new MeterIdPrefix("armeria.server.router.virtualHostCache",
                                                                "hostnamePattern", hostnamePattern)
                                            : new MeterIdPrefix("armeria.server.router.virtual.host.cache",
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
     * <a href="https://tools.ietf.org/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a>.
     */
    public String hostnamePattern() {
        return hostnamePattern;
    }

    /**
     * Returns the {@link SslContext} of this virtual host.
     */
    @Nullable
    public SslContext sslContext() {
        return sslContext;
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
     * @see ServiceConfig#shutdownAccessLogWriterOnStop()
     */
    public boolean shutdownAccessLogWriterOnStop() {
        return shutdownAccessLogWriterOnStop;
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
     *         {@link Routed#empty()} if there's no match.
     */
    public Routed<ServiceConfig> findServiceConfig(RoutingContext routingCtx, boolean useFallbackService) {
        final Routed<ServiceConfig> routed = router.find(requireNonNull(routingCtx, "routingCtx"));
        switch (routed.routingResultType()) {
            case MATCHED:
                return routed;
            case NOT_MATCHED:
                if (!useFallbackService) {
                    return routed;
                }
                break;
            case CORS_PREFLIGHT:
                assert routingCtx.isCorsPreflight();
                if (routed.value().handlesCorsPreflight()) {
                    // CorsService will handle the preflight request
                    // even if the service does not handle an OPTIONS method.
                    return routed;
                }
                break;
            default:
                // Never reaches here.
                throw new Error();
        }

        // Note that we did not implement this fallback mechanism inside a Router implementation like
        // CompositeRouter because we wanted to avoid caching non-existent mappings.
        return Routed.of(fallbackServiceConfig.route(),
                         RoutingResult.builder()
                                      .path(routingCtx.path())
                                      .query(routingCtx.query())
                                      .build(),
                         fallbackServiceConfig);
    }

    ServiceConfig fallbackServiceConfig() {
        return fallbackServiceConfig;
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

        return new VirtualHost(defaultHostname(), hostnamePattern(), sslContext(),
                               serviceConfigs, fallbackServiceConfig, RejectedRouteHandler.DISABLED,
                               host -> accessLogger, requestTimeoutMillis(),
                               maxRequestLength(), verboseResponses(),
                               accessLogWriter(), shutdownAccessLogWriterOnStop());
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
        buf.append(", requestTimeoutMillis: ");
        buf.append(requestTimeoutMillis());
        buf.append(", maxRequestLength: ");
        buf.append(maxRequestLength());
        buf.append(", verboseResponses: ");
        buf.append(verboseResponses());
        buf.append(", accessLogWriter: ");
        buf.append(accessLogWriter());
        buf.append(", shutdownAccessLogWriterOnStop: ");
        buf.append(shutdownAccessLogWriterOnStop());
        buf.append(')');
        return buf.toString();
    }

    String toStringWithoutTypeName() {
        return toString(false);
    }
}
