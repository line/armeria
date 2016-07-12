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

import java.util.Optional;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A {@link Service} and its {@link PathMapping} and {@link VirtualHost}.
 *
 * @see ServerConfig#serviceConfigs()
 * @see VirtualHost#serviceConfigs()
 */
public final class ServiceConfig {

    private static final Pattern LOGGER_NAME_PATTERN =
            Pattern.compile("^\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*" +
                            "(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*$");

    /** Initialized later by {@link VirtualHost} via {@link #build(VirtualHost)}. */
    private VirtualHost virtualHost;

    private final PathMapping pathMapping;
    private final String loggerName;
    private final Service<?, ?> service;

    private String fullLoggerName;

    /**
     * Creates a new instance.
     */
    public ServiceConfig(VirtualHost virtualHost, PathMapping pathMapping, Service<?, ?> service) {
        this(virtualHost, pathMapping, service, null);
    }

    /**
     * Creates a new instance.
     */
    public ServiceConfig(VirtualHost virtualHost, PathMapping pathMapping, Service<?, ?> service,
                         @Nullable String loggerName) {
        this(pathMapping, service, loggerName);
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
    }

    /**
     * Creates a new instance.
     */
    ServiceConfig(PathMapping pathMapping, Service<?, ?> service, @Nullable String loggerName) {
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.service = requireNonNull(service, "service");
        this.loggerName = loggerName != null ? validateLoggerName(loggerName, "loggerName")
                                             : defaultLoggerName(pathMapping);
    }

    static String validateLoggerName(String value, String propertyName) {
        requireNonNull(value, propertyName);
        if (!LOGGER_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(propertyName + ": " + value);
        }
        return value;
    }

    static String defaultLoggerName(PathMapping pathMapping) {
        final Optional<String> exactOpt = pathMapping.exactPath();
        if (exactOpt.isPresent()) {
            return convertPathToLoggerName(exactOpt);
        }

        final Optional<String> prefixOpt = pathMapping.prefixPath();
        return convertPathToLoggerName(prefixOpt);
    }

    private static String convertPathToLoggerName(Optional<String> servicePathOpt) {
        if (!servicePathOpt.isPresent()) {
            return "__UNKNOWN__";
        }

        String loggerName = servicePathOpt.get();
        if ("/".equals(loggerName)) {
            return "__ROOT__";
        }
        loggerName = loggerName.substring(1); // Strip the first slash.
        if (loggerName.endsWith("/")) {
            loggerName = loggerName.substring(0, loggerName.length() - 1); // Strip the last slash.
        }

        final StringBuilder buf = new StringBuilder(loggerName.length());
        boolean start = true;
        for (int i = 0; i < loggerName.length(); i++) {
            final char ch = loggerName.charAt(i);
            if (ch != '/') {
                if (start) {
                    start = false;
                    if (Character.isJavaIdentifierStart(ch)) {
                        buf.append(ch);
                    } else {
                        buf.append('_');
                        if (Character.isJavaIdentifierPart(ch)) {
                            buf.append(ch);
                        }
                    }
                } else {
                    if (Character.isJavaIdentifierPart(ch)) {
                        buf.append(ch);
                    } else {
                        buf.append('_');
                    }
                }
            } else {
                start = true;
                buf.append('.');
            }
        }

        return buf.toString();
    }

    ServiceConfig build(VirtualHost virtualHost) {
        requireNonNull(virtualHost, "virtualHost");
        return new ServiceConfig(virtualHost, pathMapping(), service());
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
     * Returns the {@link PathMapping} of the {@link #service()}.
     */
    public PathMapping pathMapping() {
        return pathMapping;
    }

    /**
     * Returns the {@link Service}.
     */
    @SuppressWarnings("unchecked")
    public <T extends Service<?, ?>> T service() {
        return (T) service;
    }

    /**
     * Returns the name of the {@link ServiceRequestContext#logger() service logger}.
     */
    public String loggerName() {
        if (fullLoggerName == null) {
            fullLoggerName = virtualHost().server().config().serviceLoggerPrefix() + '.' + loggerName;
        }

        return fullLoggerName;
    }

    String loggerNameWithoutPrefix() {
        return loggerName;
    }

    @Override
    public String toString() {
        if (virtualHost != null) {
            return virtualHost.hostnamePattern() + ':' +
                   pathMapping + " -> " + service + " (" + loggerName + ')';
        } else {
            return pathMapping + " -> " + service + " (" + loggerName + ')';
        }
    }
}
