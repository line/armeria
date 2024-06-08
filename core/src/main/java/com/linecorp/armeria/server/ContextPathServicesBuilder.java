/*
 * Copyright 2023 LINE Corporation
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

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * Builds {@link ServiceConfig}s for a {@link ServerBuilder}.
 * All {@link ServiceConfig}s built by this builder will be served under the specified context paths.
 *
 * <pre>{@code
 * Server.builder()
 *       .contextPath("/v1", "/v2")
 *       .service(myService) // served under "/v1" and "/v2"
 * }</pre>
 */
@UnstableApi
public final class ContextPathServicesBuilder
        extends AbstractContextPathServicesBuilder<ContextPathServicesBuilder, ServerBuilder> {

    ContextPathServicesBuilder(ServerBuilder parent, VirtualHostBuilder virtualHostBuilder,
                               Set<String> contextPaths) {
        super(parent, virtualHostBuilder, contextPaths);
    }

    /**
     * Configures an {@link HttpService} under the context path with the {@code customizer}.
     */
    public ContextPathServicesBuilder withRoute(
            Consumer<? super ContextPathServiceBindingBuilder> customizer) {
        requireNonNull(customizer, "customizer");
        customizer.accept(new ContextPathServiceBindingBuilder(this));
        return this;
    }

    /**
     * Returns a {@link ContextPathServiceBindingBuilder} which is for binding
     * an {@link HttpService} fluently.
     */
    @Override
    public ContextPathServiceBindingBuilder route() {
        return new ContextPathServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link ContextPathDecoratingBindingBuilder} which is for binding
     * a {@code decorator} fluently.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public ContextPathDecoratingBindingBuilder routeDecorator() {
        return new ContextPathDecoratingBindingBuilder(this);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(
            String pathPrefix, Object service,
            Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        return annotatedService().pathPrefix(pathPrefix)
                                 .decorator(decorator)
                                 .exceptionHandlers(exceptionHandlerFunctions)
                                 .requestConverters(requestConverterFunctions)
                                 .responseConverters(responseConverterFunctions)
                                 .build(service);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters annotatedService() {
        return new ContextPathAnnotatedServiceConfigSetters(this);
    }
}
