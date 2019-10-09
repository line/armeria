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

abstract class AbstractServiceBuilder implements ServiceBuilder {

    private final DefaultServiceBuilder defaultServiceBuilder = new DefaultServiceBuilder();

    @Override
    public AbstractServiceBuilder requestTimeout(Duration requestTimeout) {
        defaultServiceBuilder.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public AbstractServiceBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceBuilder.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public AbstractServiceBuilder maxRequestLength(long maxRequestLength) {
         defaultServiceBuilder.maxRequestLength(maxRequestLength);
         return this;
    }

    @Override
    public AbstractServiceBuilder verboseResponses(boolean verboseResponses) {
        defaultServiceBuilder.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public AbstractServiceBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceBuilder.requestContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AbstractServiceBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceBuilder.responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AbstractServiceBuilder contentPreview(int length) {
        defaultServiceBuilder.contentPreview(length);
        return this;
    }

    @Override
    public AbstractServiceBuilder contentPreview(int length, Charset defaultCharset) {
        contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
        return this;
    }

    @Override
    public AbstractServiceBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public AbstractServiceBuilder accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
            true);
    }

    @Override
    public AbstractServiceBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                  boolean shutdownOnStop) {
        defaultServiceBuilder.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    AbstractServiceBuilder decorator(Function<T, R> decorator) {
        defaultServiceBuilder.decorator(decorator);
        return this;
    }

    public Service<HttpRequest, HttpResponse> decorate(Service<HttpRequest, HttpResponse> service) {
        return defaultServiceBuilder.decorate(service);
    }

    final void build0(Route route, Service<HttpRequest, HttpResponse> service) {
        final ServiceConfigBuilder serviceConfigBuilder = defaultServiceBuilder.serviceConfigBuilder(route,
                                                                                                     service);
        serviceConfigBuilder(serviceConfigBuilder);
    }

    abstract void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder);
}
