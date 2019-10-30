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

import static com.linecorp.armeria.server.ServerConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateRequestTimeoutMillis;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A default implementation of {@link ServiceConfigSetters} that stores service related settings
 * and provides a method {@link DefaultServiceConfigSetters#toServiceConfigBuilder(Route, Service)} to build
 * {@link ServiceConfigBuilder}.
 */
final class DefaultServiceConfigSetters implements ServiceConfigSetters {
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
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;
    @Nullable
    private AccessLogWriter accessLogWriter;
    private boolean shutdownAccessLogWriterOnStop;

    @Override
    public ServiceConfigSetters requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public ServiceConfigSetters requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public ServiceConfigSetters maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public ServiceConfigSetters verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    @Override
    public ServiceConfigSetters requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    @Override
    public ServiceConfigSetters responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        responseContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    @Override
    public ServiceConfigSetters contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    @Override
    public ServiceConfigSetters contentPreview(int length, Charset defaultCharset) {
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    @Override
    public ServiceConfigSetters contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public ServiceConfigSetters accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
    }

    @Override
    public ServiceConfigSetters accessLogWriter(AccessLogWriter accessLogWriter,
                                          boolean shutdownOnStop) {
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        shutdownAccessLogWriterOnStop = shutdownOnStop;
        return this;
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    ServiceConfigSetters decorator(Function<T, R> decorator) {
        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return this;
    }

    Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> getDecorator() {
        if (decorator == null) {
            return Function.identity();
        }
        return decorator;
    }

    Service<HttpRequest, HttpResponse> decorate(Service<HttpRequest, HttpResponse> service) {
        if (decorator == null) {
            return service;
        }
        return service.decorate(decorator);
    }

    /**
     * Note: {@link ServiceConfigBuilder} built by this method is not decorated with the decorator function
     * which can be configured using {@link DefaultServiceConfigSetters#decorate(Service)} because
     * {@link AnnotatedServiceBindingBuilder} needs exception handling decorators to be the last to handle
     * any exceptions thrown by the service and other decorators.
     */
    ServiceConfigBuilder toServiceConfigBuilder(Route route, Service<HttpRequest, HttpResponse> service) {
        final ServiceConfigBuilder serviceConfigBuilder = new ServiceConfigBuilder(route, service);
        if (requestTimeoutMillis != null) {
            serviceConfigBuilder.requestTimeoutMillis(requestTimeoutMillis);
        }
        if (maxRequestLength != null) {
            serviceConfigBuilder.maxRequestLength(maxRequestLength);
        }
        if (verboseResponses != null) {
            serviceConfigBuilder.verboseResponses(verboseResponses);
        }
        if (requestContentPreviewerFactory != null) {
            serviceConfigBuilder.requestContentPreviewerFactory(requestContentPreviewerFactory);
        }
        if (responseContentPreviewerFactory != null) {
            serviceConfigBuilder.responseContentPreviewerFactory(responseContentPreviewerFactory);
        }
        if (accessLogWriter != null) {
            serviceConfigBuilder.accessLogWriter(accessLogWriter, shutdownAccessLogWriterOnStop);
        }
        return serviceConfigBuilder;
    }
}
