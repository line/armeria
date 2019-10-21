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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder that implements {@link ServiceConfigSetters} by delegating all calls
 * to {@link DefaultServiceConfigSetters}.
 */
abstract class AbstractServiceConfigSetters implements ServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();

    @Override
    public AbstractServiceConfigSetters requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.requestContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters contentPreview(int length) {
        defaultServiceConfigSetters.contentPreview(length);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters contentPreview(int length, Charset defaultCharset) {
        contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
        return this;
    }

    @Override
    public AbstractServiceConfigSetters contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AbstractServiceConfigSetters accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
    }

    @Override
    public AbstractServiceConfigSetters accessLogWriter(AccessLogWriter accessLogWriter,
                                                  boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    AbstractServiceConfigSetters decorator(Function<T, R> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    Service<HttpRequest, HttpResponse> decorate(Service<HttpRequest, HttpResponse> service) {
        return defaultServiceConfigSetters.decorate(service);
    }

    final void build0(Route route, Service<HttpRequest, HttpResponse> service) {
        final ServiceConfigBuilder serviceConfigBuilder = defaultServiceConfigSetters
                                                                .toServiceConfigBuilder(route, service);
        serviceConfigBuilder(serviceConfigBuilder);
    }

    abstract void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder);
}
