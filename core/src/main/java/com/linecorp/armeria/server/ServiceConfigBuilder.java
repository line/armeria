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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.AccessLogWriter;

final class ServiceConfigBuilder {

    private final Route route;
    private final HttpService service;
    @Nullable
    private String loggerName;

    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private ContentPreviewerFactory requestContentPreviewerFactory;
    @Nullable
    private ContentPreviewerFactory responseContentPreviewerFactory;
    @Nullable
    private AccessLogWriter accessLogWriter;
    private boolean shutdownAccessLogWriterOnStop;

    ServiceConfigBuilder(Route route, HttpService service) {
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
    }

    ServiceConfigBuilder loggerName(String loggerName) {
        this.loggerName = requireNonNull(loggerName, "loggerName");
        return this;
    }

    @Nullable
    Long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    ServiceConfigBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        return this;
    }

    @Nullable
    Long maxRequestLength() {
        return maxRequestLength;
    }

    ServiceConfigBuilder maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = maxRequestLength;
        return this;
    }

    @Nullable
    Boolean verboseResponses() {
        return verboseResponses;
    }

    ServiceConfigBuilder verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    @Nullable
    ContentPreviewerFactory requestContentPreviewerFactory() {
        return requestContentPreviewerFactory;
    }

    ServiceConfigBuilder requestContentPreviewerFactory(
            ContentPreviewerFactory requestContentPreviewerFactory) {
        this.requestContentPreviewerFactory = requestContentPreviewerFactory;
        return this;
    }

    @Nullable
    ContentPreviewerFactory responseContentPreviewerFactory() {
        return responseContentPreviewerFactory;
    }

    ServiceConfigBuilder responseContentPreviewerFactory(
            ContentPreviewerFactory responseContentPreviewerFactory) {
        this.responseContentPreviewerFactory = responseContentPreviewerFactory;
        return this;
    }

    @Nullable
    AccessLogWriter accessLogWriter() {
        return accessLogWriter;
    }

    ServiceConfigBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        this.accessLogWriter = accessLogWriter;
        shutdownAccessLogWriterOnStop = shutdownOnStop;
        return this;
    }

    ServiceConfig build(long defaultRequestTimeoutMillis,
                        long defaultMaxRequestLength,
                        boolean defaultVerboseResponses,
                        ContentPreviewerFactory defaultRequestContentPreviewerFactory,
                        ContentPreviewerFactory defaultResponseContentPreviewerFactory,
                        AccessLogWriter defaultAccessLogWriter,
                        boolean defaultShutdownAccessLogWriterOnStop) {
        return new ServiceConfig(
                route, service, loggerName,
                requestTimeoutMillis != null ? requestTimeoutMillis : defaultRequestTimeoutMillis,
                maxRequestLength != null ? maxRequestLength : defaultMaxRequestLength,
                verboseResponses != null ? verboseResponses : defaultVerboseResponses,
                requestContentPreviewerFactory != null ? requestContentPreviewerFactory
                                                       : defaultRequestContentPreviewerFactory,
                responseContentPreviewerFactory != null ? responseContentPreviewerFactory
                                                        : defaultResponseContentPreviewerFactory,
                accessLogWriter != null ? accessLogWriter : defaultAccessLogWriter,
                accessLogWriter != null ? shutdownAccessLogWriterOnStop : defaultShutdownAccessLogWriterOnStop);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("route", route)
                          .add("service", service)
                          .add("loggerName", loggerName)
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
