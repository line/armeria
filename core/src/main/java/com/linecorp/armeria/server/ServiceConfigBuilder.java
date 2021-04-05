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

import java.time.Duration;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.server.logging.AccessLogWriter;

final class ServiceConfigBuilder implements ServiceConfigSetters {

    private final Route route;
    private final HttpService service;

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
    private boolean shutdownAccessLogWriterOnStop;

    ServiceConfigBuilder(Route route, HttpService service) {
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
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
        this.accessLogWriter = accessLogWriter;
        shutdownAccessLogWriterOnStop = shutdownOnStop;
        return this;
    }

    @Override
    public ServiceConfigBuilder accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
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
    public ServiceConfigBuilder defaultServiceName(String defaultServiceName) {
        requireNonNull(defaultServiceName, "defaultServiceName");
        this.defaultServiceName = defaultServiceName;
        return defaultServiceNaming(ServiceNaming.of(defaultServiceName));
    }

    @Override
    public ServiceConfigBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        this.defaultServiceNaming = requireNonNull(defaultServiceNaming, "defaultServiceNaming");
        return this;
    }

    ServiceConfig build(ServiceNaming defaultServiceNaming,
                        long defaultRequestTimeoutMillis,
                        long defaultMaxRequestLength,
                        boolean defaultVerboseResponses,
                        AccessLogWriter defaultAccessLogWriter,
                        boolean defaultShutdownAccessLogWriterOnStop) {
        return new ServiceConfig(
                route, service, defaultLogName, defaultServiceName,
                this.defaultServiceNaming != null ? this.defaultServiceNaming : defaultServiceNaming,
                requestTimeoutMillis != null ? requestTimeoutMillis : defaultRequestTimeoutMillis,
                maxRequestLength != null ? maxRequestLength : defaultMaxRequestLength,
                verboseResponses != null ? verboseResponses : defaultVerboseResponses,
                accessLogWriter != null ? accessLogWriter : defaultAccessLogWriter,
                accessLogWriter != null ? shutdownAccessLogWriterOnStop : defaultShutdownAccessLogWriterOnStop);
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
                          .add("shutdownAccessLogWriterOnStop", shutdownAccessLogWriterOnStop)
                          .toString();
    }
}
