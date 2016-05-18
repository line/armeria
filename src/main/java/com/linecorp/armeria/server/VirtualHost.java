/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.kristofa.brave.internal.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;

/**
 * A <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
 * A {@link VirtualHost} contains the following information:
 * <ul>
 *   <li>the hostname pattern, as defined in
 *       <a href="http://tools.ietf.org/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a></li>
 *   <li>{@link SslContext} if TLS is enabled</li>
 *   <li>the list of available {@link Service}s and their {@link PathMapping}s</li>
 * </ul>
 *
 * @see VirtualHostBuilder
 */
public final class VirtualHost {

    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?:[-_a-zA-Z0-9]|[-_a-zA-Z0-9][-_.a-zA-Z0-9]*[-_a-zA-Z0-9])$");

    /** Initialized later by {@link ServerConfig} via {@link #setServerConfig(ServerConfig)}. */
    private ServerConfig serverConfig;

    private final String defaultHostname;
    private final String hostnamePattern;
    private final SslContext sslContext;
    private final List<ServiceConfig> services;
    private final PathMappings<ServiceConfig> serviceMapping = new PathMappings<>();

    private String strVal;

    VirtualHost(String defaultHostname, String hostnamePattern,
                SslContext sslContext, Iterable<ServiceConfig> serviceConfigs) {

        defaultHostname = normalizeDefaultHostname(defaultHostname);
        hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        ensureHostnamePatternMatchesDefaultHostname(hostnamePattern, defaultHostname);

        this.defaultHostname = defaultHostname;
        this.hostnamePattern = hostnamePattern;
        this.sslContext = validateSslContext(sslContext);

        requireNonNull(serviceConfigs, "serviceConfigs");

        final List<ServiceConfig> servicesCopy = new ArrayList<>();

        for (ServiceConfig c : serviceConfigs) {
            c = c.build(this);
            servicesCopy.add(c);
            serviceMapping.add(c.pathMapping(), c);
        }

        services = Collections.unmodifiableList(servicesCopy);
        serviceMapping.freeze();
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

        return defaultHostname.toLowerCase(Locale.ENGLISH);
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

        return hostnamePattern.toLowerCase(Locale.ENGLISH);
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
        for (int i = 0; i < length; i ++) {
            int c = hostnamePattern.charAt(i);
            if (c > 0x7F) {
                return true;
            }
        }
        return false;
    }

    static SslContext validateSslContext(SslContext sslContext) {
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
    }

    /**
     * Returns the default hostname of this virtual host.
     */
    public String defaultHostname() {
        return defaultHostname;
    }
    /**
     * Returns the hostname pattern of this virtual host, as defined in
     * <a href="http://tools.ietf.org/html/rfc2818#section-3.1">the section 3.1 of RFC2818</a>
     */
    public String hostnamePattern() {
        return hostnamePattern;
    }

    /**
     * Returns the {@link SslContext} of this virtual host.
     */
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
     * Finds the {@link Service} whose {@link PathMapping} matches the {@code path}.
     *
     * @return the {@link Service} wrapped by {@link PathMapped} if there's a match.
     *         {@link PathMapped#empty()} if there's no match.
     */
    public PathMapped<ServiceConfig> findServiceConfig(String path) {
        return serviceMapping.apply(path);
    }

    VirtualHost decorate(@Nullable Function<Service<Request, Response>, Service<Request, Response>> decorator) {
        if (decorator == null) {
            return this;
        }

        final List<ServiceConfig> services =
                this.services.stream().map(cfg -> {
                    final PathMapping pathMapping = cfg.pathMapping();
                    final Service<Request, Response> service = decorator.apply(cfg.service());
                    final String loggerName = cfg.loggerNameWithoutPrefix();
                    return new ServiceConfig(pathMapping, service, loggerName);
                }).collect(Collectors.toList());

        return new VirtualHost(defaultHostname(), hostnamePattern(), sslContext(), services);
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

    static String toString(Class<?> type, String defaultHostname, String hostnamePattern,
                           SslContext sslContext, List<?> services) {

        StringBuilder buf = new StringBuilder();
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
