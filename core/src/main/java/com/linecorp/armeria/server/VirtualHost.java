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
import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaTypeSet;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;

/**
 * A <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
 * A {@link VirtualHost} contains the following information:
 * <ul>
 *   <li>the hostname pattern, as defined in
 *       <a href="https://tools.ietf.org/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a></li>
 *   <li>{@link SslContext} if TLS is enabled</li>
 *   <li>the list of available {@link Service}s and their {@link PathMapping}s</li>
 * </ul>
 *
 * @see VirtualHostBuilder
 */
public final class VirtualHost {

    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?:[-_a-zA-Z0-9]|[-_a-zA-Z0-9][-_.a-zA-Z0-9]*[-_a-zA-Z0-9])$");

    /**
     * Initialized later by {@link ServerConfig} via {@link #setServerConfig(ServerConfig)}.
     */
    @Nullable
    private ServerConfig serverConfig;

    private final String defaultHostname;
    private final String hostnamePattern;
    @Nullable
    private final SslContext sslContext;
    private final List<ServiceConfig> services;
    private final Router<ServiceConfig> router;
    private final MediaTypeSet producibleMediaTypes;

    /**
     * If {@code accessLogger} is {@code null}, it is initialized later
     * by {@link ServerBuilder} via {@link #accessLogger(Logger)}.
     */
    @Nullable
    private Logger accessLogger;

    @Nullable
    private String strVal;

    /**
     * Use this constructor when you are sure that the {@link ServiceConfig}s have no duplicate
     * {@link PathMapping}s or it's OK to have them. This is useful when you create a new {@link VirtualHost}
     * from an existing {@link VirtualHost}, because its {@link ServiceConfig}s were validated already.
     */
    VirtualHost(String defaultHostname, String hostnamePattern,
                @Nullable SslContext sslContext, Iterable<ServiceConfig> serviceConfigs,
                MediaTypeSet producibleMediaTypes) {
        this(defaultHostname, hostnamePattern, sslContext, serviceConfigs, producibleMediaTypes,
             (virtualHost, mapping, existingMapping) -> {}, null);
    }

    VirtualHost(String defaultHostname, String hostnamePattern,
                @Nullable SslContext sslContext, Iterable<ServiceConfig> serviceConfigs,
                MediaTypeSet producibleMediaTypes, Function<VirtualHost, Logger> accessLoggerMapper) {
        this(defaultHostname, hostnamePattern, sslContext, serviceConfigs, producibleMediaTypes,
             (virtualHost, mapping, existingMapping) -> {}, accessLoggerMapper);
    }

    VirtualHost(String defaultHostname, String hostnamePattern,
                @Nullable SslContext sslContext, Iterable<ServiceConfig> serviceConfigs,
                MediaTypeSet producibleMediaTypes, RejectedPathMappingHandler rejectionHandler) {
        this(defaultHostname, hostnamePattern, sslContext, serviceConfigs, producibleMediaTypes,
             rejectionHandler, null);
    }

    VirtualHost(String defaultHostname, String hostnamePattern,
                @Nullable SslContext sslContext, Iterable<ServiceConfig> serviceConfigs,
                MediaTypeSet producibleMediaTypes, RejectedPathMappingHandler rejectionHandler,
                Function<VirtualHost, Logger> accessLoggerMapper) {

        defaultHostname = normalizeDefaultHostname(defaultHostname);
        hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        ensureHostnamePatternMatchesDefaultHostname(hostnamePattern, defaultHostname);

        this.defaultHostname = defaultHostname;
        this.hostnamePattern = hostnamePattern;
        this.sslContext = validateSslContext(sslContext);
        this.producibleMediaTypes = producibleMediaTypes;

        requireNonNull(serviceConfigs, "serviceConfigs");

        final List<ServiceConfig> servicesCopy = new ArrayList<>();

        for (ServiceConfig c : serviceConfigs) {
            c = c.build(this);
            servicesCopy.add(c);
        }

        services = Collections.unmodifiableList(servicesCopy);
        router = Routers.ofVirtualHost(this, services, rejectionHandler);
        if (accessLoggerMapper != null) {
            accessLogger = accessLoggerMapper.apply(this);
            checkState(accessLogger != null,
                       "accessLoggerMapper.apply() has returned null for virtual host: %s.", hostnamePattern);
        }
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
        final DomainNameMapping<Boolean> mapping =
                new DomainNameMappingBuilder<>(Boolean.FALSE).add(hostnamePattern, Boolean.TRUE).build();

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

    @Nullable
    static SslContext validateSslContext(@Nullable SslContext sslContext) {
        if (sslContext != null && !sslContext.isServer()) {
            throw new IllegalArgumentException("sslContext: " + sslContext + " (expected: server context)");
        }
        return sslContext;
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
        final MeterIdPrefix idPrefix = new MeterIdPrefix("armeria.server.router.virtualHostCache",
                                                         "hostnamePattern", hostnamePattern);
        router.registerMetrics(registry, idPrefix);
    }

    /**
     * Sets the {@link Logger} which is used for writing access logs of this virtual host.
     */
    void accessLogger(Logger logger) {
        accessLogger = requireNonNull(logger, "logger");
    }

    /**
     * Returns the {@link Logger} which is used for writing access logs of this virtual host.
     */
    public Logger accessLogger() {
        checkState(accessLogger != null, "accessLogger not initialized yet.");
        return accessLogger;
    }

    @Nullable
    Logger accessLoggerOrNull() {
        return accessLogger;
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
     * Returns the information about the {@link Service}s bound to this virtual host.
     */
    public List<ServiceConfig> serviceConfigs() {
        return services;
    }

    /**
     * Returns {@link MediaTypeSet} that consists of media types producible by this virtual host.
     */
    public MediaTypeSet producibleMediaTypes() {
        return producibleMediaTypes;
    }

    /**
     * Finds the {@link Service} whose {@link Router} matches the {@link PathMappingContext}.
     *
     * @param mappingCtx a context to find the {@link Service}.
     *
     * @return the {@link ServiceConfig} wrapped by a {@link PathMapped} if there's a match.
     *         {@link PathMapped#empty()} if there's no match.
     */
    public PathMapped<ServiceConfig> findServiceConfig(PathMappingContext mappingCtx) {
        requireNonNull(mappingCtx, "mappingCtx");
        return router.find(mappingCtx);
    }

    private Router<ServiceConfig> router() {
        return router;
    }

    VirtualHost decorate(@Nullable Function<Service<HttpRequest, HttpResponse>,
                                            Service<HttpRequest, HttpResponse>> decorator) {
        if (decorator == null) {
            return this;
        }

        final List<ServiceConfig> services =
                this.services.stream().map(cfg -> {
                    final PathMapping pathMapping = cfg.pathMapping();
                    final Service<HttpRequest, HttpResponse> service = decorator.apply(cfg.service());
                    final String loggerName = cfg.loggerName().orElse(null);
                    return new ServiceConfig(pathMapping, service, loggerName);
                }).collect(Collectors.toList());

        return new VirtualHost(defaultHostname(), hostnamePattern(), sslContext(),
                               services, producibleMediaTypes());
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal == null) {
            this.strVal = strVal = toString(
                    getClass(), defaultHostname(), hostnamePattern(), sslContext(), serviceConfigs());
        }

        return strVal;
    }

    static String toString(@Nullable Class<?> type, String defaultHostname, String hostnamePattern,
                           @Nullable SslContext sslContext, List<?> services) {

        final StringBuilder buf = new StringBuilder();
        if (type != null) {
            buf.append(type.getSimpleName());
        }

        buf.append('(');
        buf.append(defaultHostname);
        buf.append('/');
        buf.append(hostnamePattern);
        buf.append(", ssl: ");
        buf.append(sslContext != null);
        buf.append(", services: ");
        buf.append(services);
        buf.append(')');

        return buf.toString();
    }
}
