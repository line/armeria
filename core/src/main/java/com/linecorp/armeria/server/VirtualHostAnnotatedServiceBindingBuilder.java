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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceConfiguratorSetters;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding a {@link Service} to a virtual host fluently. This class can be instantiated
 * through {@link VirtualHostBuilder#annotatedService()}.
 *
 * <p>Call {@link #build(Object)} to build the {@link Service} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.virtualHost("foo.com")                       // Return a new instance of {@link VirtualHostBuilder}
 *   .annotatedService()                           // Return a new instance of this class
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
 *   .pathPrefix("/foo")
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .build(new FooService())                      // Return to {@link VirtualHostBuilder}
 *   .and()                                        // Return to {@link ServerBuilder}
 *   .annotatedService(new MyDefaultHostService())
 *   .build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 * @see AnnotatedServiceBindingBuilder
 */
public final class VirtualHostAnnotatedServiceBindingBuilder implements ServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();
    private final VirtualHostBuilder virtualHostBuilder;
    private final Builder<ExceptionHandlerFunction> exceptionHandlerFunctionBuilder = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverterFunctionBuilder = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverterFunctionBuilder = ImmutableList.builder();
    @Nullable
    private Consumer<AnnotatedHttpServiceConfiguratorSetters> configuratorCustomizer;
    private boolean canSetFunctionBuilder = true;
    private boolean canSetConfiguratorCustomizer = true;
    private String pathPrefix = "/";

    VirtualHostAnnotatedServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = virtualHostBuilder;
    }

    /**
     * Sets the path prefix to be used for this {@link VirtualHostAnnotatedServiceBindingBuilder}.
     * @param pathPrefix string representing the path prefix.
     */
    public VirtualHostAnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    /**
     * Adds the given {@link ExceptionHandlerFunction} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder exceptionHandler(
            ExceptionHandlerFunction exceptionHandlerFunction) {
        requireNonNull(exceptionHandlerFunction, "exceptionHandler");
        checkState(canSetFunctionBuilder,
                   "Cannot call exceptionHandler() if called customizer() already.");
        exceptionHandlerFunctionBuilder.add(exceptionHandlerFunction);
        canSetConfiguratorCustomizer = false;
        return this;
    }

    /**
     * Adds the given {@link ResponseConverterFunction} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder responseConverter(
            ResponseConverterFunction responseConverterFunction) {
        requireNonNull(responseConverterFunction, "responseConverterFunction");
        checkState(canSetFunctionBuilder,
                   "Cannot call responseConverter() if called customizer() already.");
        responseConverterFunctionBuilder.add(responseConverterFunction);
        canSetConfiguratorCustomizer = false;
        return this;
    }

    /**
     * Adds the given {@link RequestConverterFunction} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder requestConverter(
            RequestConverterFunction requestConverterFunction) {
        requireNonNull(requestConverterFunction, "requestConverterFunction");
        checkState(canSetFunctionBuilder,
                   "Cannot call requestConverter() if called customizer() already.");
        requestConverterFunctionBuilder.add(requestConverterFunction);
        canSetConfiguratorCustomizer = false;
        return this;
    }

    /**
     * Adds the given {@link Consumer} which customizes the given
     * {@link AnnotatedHttpServiceConfiguratorSetters} to this
     * {@link VirtualHostAnnotatedServiceBindingBuilder}.
     */
    public VirtualHostAnnotatedServiceBindingBuilder configuratorCustomizer(
            Consumer<AnnotatedHttpServiceConfiguratorSetters> configuratorCustomizer) {
        requireNonNull(configuratorCustomizer, "customizer");
        checkState(canSetConfiguratorCustomizer,
                   "Cannot call customizer() if called exceptionHandler()," +
                   " responseConverter(), requestConverter() already.");
        this.configuratorCustomizer = configuratorCustomizer;
        canSetFunctionBuilder = true;
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        defaultServiceConfigSetters.requestTimeout(requestTimeout);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder requestContentPreviewerFactory(
            ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.requestContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder responseContentPreviewerFactory(
            ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.responseContentPreviewerFactory(factory);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contentPreview(int length) {
        defaultServiceConfigSetters.contentPreview(length);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        defaultServiceConfigSetters.contentPreview(length, defaultCharset);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        defaultServiceConfigSetters.contentPreviewerFactory(factory);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        defaultServiceConfigSetters.accessLogFormat(accessLogFormat);
        return this;
    }

    @Override
    public VirtualHostAnnotatedServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                                     boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostAnnotatedServiceBindingBuilder decorator(Function<T, R> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    /**
     * Registers the given service to the {@linkplain VirtualHostBuilder}.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     * @return {@link VirtualHostBuilder} to continue building {@link VirtualHost}
     */
    public VirtualHostBuilder build(Object service) {
        final List<AnnotatedHttpServiceElement> elements = find(service);
        elements.forEach(element -> {
            final Service<HttpRequest, HttpResponse> decoratedService =
                    element.buildSafeDecoratedService(defaultServiceConfigSetters.getDecorator());
            final ServiceConfigBuilder serviceConfigBuilder =
                    defaultServiceConfigSetters.toServiceConfigBuilder(element.route(), decoratedService);
            virtualHostBuilder.serviceConfigBuilder(serviceConfigBuilder);
        });
        return virtualHostBuilder;
    }

    private List<AnnotatedHttpServiceElement> find(Object service) {
        if (configuratorCustomizer != null) {
            return AnnotatedHttpServiceFactory.find(pathPrefix, service, configuratorCustomizer);
        } else {
            return AnnotatedHttpServiceFactory.find(pathPrefix, service,
                                                    exceptionHandlerFunctionBuilder.build(),
                                                    requestConverterFunctionBuilder.build(),
                                                    responseConverterFunctionBuilder.build());
        }
    }
}
