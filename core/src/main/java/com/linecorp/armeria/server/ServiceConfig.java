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

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A {@link Service} configuration.
 *
 * @see ServerConfig#serviceConfigs()
 * @see VirtualHost#serviceConfigs()
 */
public final class ServiceConfig {

    private static final Pattern LOGGER_NAME_PATTERN =
            Pattern.compile("^\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*" +
                            "(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*$");

    @Nullable
    private final VirtualHost virtualHost;

    private final Route route;
    @Nullable
    private final String loggerName;
    private final Service<HttpRequest, HttpResponse> service;

    private final long requestTimeoutMillis;
    private final long maxRequestLength;
    private final boolean verboseResponses;

    private final ContentPreviewerFactory requestContentPreviewerFactory;
    private final ContentPreviewerFactory responseContentPreviewerFactory;
    private final AccessLogWriter accessLogWriter;
    private final boolean shutdownAccessLogWriterOnStop;

    /**
     * Creates a new instance.
     */
    ServiceConfig(Route route,
                  Service<HttpRequest, HttpResponse> service,
                  @Nullable String loggerName, long requestTimeoutMillis,
                  long maxRequestLength, boolean verboseResponses,
                  ContentPreviewerFactory requestContentPreviewerFactory,
                  ContentPreviewerFactory responseContentPreviewerFactory, AccessLogWriter accessLogWriter,
                  boolean shutdownAccessLogWriterOnStop) {
        this(null, route, service, loggerName, requestTimeoutMillis, maxRequestLength,
             verboseResponses, requestContentPreviewerFactory, responseContentPreviewerFactory,
             accessLogWriter, shutdownAccessLogWriterOnStop);
    }

    /**
     * Creates a new instance.
     */
    private ServiceConfig(@Nullable VirtualHost virtualHost, Route route,
                          Service<HttpRequest, HttpResponse> service,
                          @Nullable String loggerName, long requestTimeoutMillis,
                          long maxRequestLength, boolean verboseResponses,
                          ContentPreviewerFactory requestContentPreviewerFactory,
                          ContentPreviewerFactory responseContentPreviewerFactory,
                          AccessLogWriter accessLogWriter, boolean shutdownAccessLogWriterOnStop) {
        this.virtualHost = virtualHost;
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
        this.loggerName = loggerName != null ? validateLoggerName(loggerName, "loggerName") : null;
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        this.verboseResponses = verboseResponses;
        this.requestContentPreviewerFactory = requireNonNull(requestContentPreviewerFactory,
                                                             "requestContentPreviewerFactory");
        this.responseContentPreviewerFactory = requireNonNull(responseContentPreviewerFactory,
                                                              "responseContentPreviewerFactory");
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        this.shutdownAccessLogWriterOnStop = shutdownAccessLogWriterOnStop;
    }

    static String validateLoggerName(String value, String propertyName) {
        requireNonNull(value, propertyName);
        if (!LOGGER_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(propertyName + ": " + value);
        }
        return value;
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
        return new ServiceConfig(virtualHost, route, service, loggerName, requestTimeoutMillis,
                                 maxRequestLength, verboseResponses, requestContentPreviewerFactory,
                                 responseContentPreviewerFactory, accessLogWriter,
                                 shutdownAccessLogWriterOnStop);
    }

    ServiceConfig withDecoratedService(
            Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator) {
        requireNonNull(decorator, "decorator");
        return new ServiceConfig(virtualHost, route, service.decorate(decorator), loggerName,
                                 requestTimeoutMillis, maxRequestLength, verboseResponses,
                                 requestContentPreviewerFactory, responseContentPreviewerFactory,
                                 accessLogWriter, shutdownAccessLogWriterOnStop);
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
     * Returns the {@link Service}.
     */
    @SuppressWarnings("unchecked")
    public <T extends Service<HttpRequest, HttpResponse>> T service() {
        return (T) service;
    }

    /**
     * Returns the logger name for the {@link Service}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    public Optional<String> loggerName() {
        return Optional.ofNullable(loggerName);
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
     * Returns the {@link ContentPreviewerFactory} used for creating a new {@link ContentPreviewer}
     * which produces the request content preview of this {@link Service}.
     *
     * @see VirtualHost#requestContentPreviewerFactory()
     */
    public ContentPreviewerFactory requestContentPreviewerFactory() {
        return requestContentPreviewerFactory;
    }

    /**
     * Returns the {@link ContentPreviewerFactory} used for creating a new {@link ContentPreviewer}
     * which produces the response content preview of this {@link Service}.
     *
     * @see VirtualHost#responseContentPreviewerFactory()
     */
    public ContentPreviewerFactory responseContentPreviewerFactory() {
        return responseContentPreviewerFactory;
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
     * @see VirtualHost#shutdownAccessLogWriterOnStop()
     */
    public boolean shutdownAccessLogWriterOnStop() {
        return shutdownAccessLogWriterOnStop;
    }

    @Override
    public String toString() {
        final ToStringHelper toStringHelper = MoreObjects.toStringHelper(this).omitNullValues();
        if (virtualHost != null) {
            toStringHelper.add("hostnamePattern", virtualHost.hostnamePattern());
        }
        return toStringHelper.add("route", route)
                             .add("loggerName", loggerName)
                             .add("service", service)
                             .add("requestTimeoutMillis", requestTimeoutMillis)
                             .add("maxRequestLength", maxRequestLength)
                             .add("verboseResponses", verboseResponses)
                             .add("requestContentPreviewerFactory", requestContentPreviewerFactory)
                             .add("responseContentPreviewerFactory", responseContentPreviewerFactory)
                             .add("accessLogWriter", accessLogWriter)
                             .add("shutdownAccessLogWriterOnStop", shutdownAccessLogWriterOnStop)
                             .toString();
    }
}
