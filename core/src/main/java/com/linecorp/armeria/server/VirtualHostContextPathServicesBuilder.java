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
 * Builds {@link ServiceConfig}s for a {@link VirtualHostBuilder}.
 * All {@link ServiceConfig}s built by this builder will be served under the specified context paths.
 *
 * <pre>{@code
 * Server.builder()
 *       .virtualHost("foo.com")
 *       .contextPath("/v3")
 *       .service(myService) // served by virtual host "foo.com" under "/v3"
 * }</pre>
 */
@UnstableApi
public final class VirtualHostContextPathServicesBuilder
        extends AbstractContextPathServicesBuilder<VirtualHostContextPathServicesBuilder, VirtualHostBuilder> {

    VirtualHostContextPathServicesBuilder(VirtualHostBuilder parent, VirtualHostBuilder virtualHostBuilder,
                                          Set<String> contextPaths) {
        super(parent, virtualHostBuilder, contextPaths);
    }

    /**
     * Configures an {@link HttpService} under the context path with the {@code customizer}.
     */
    public VirtualHostContextPathServicesBuilder withRoute(
            Consumer<? super VirtualHostContextPathServiceBindingBuilder> customizer) {
        customizer.accept(new VirtualHostContextPathServiceBindingBuilder(this));
        return this;
    }

    /**
     * Returns a {@link VirtualHostContextPathServiceBindingBuilder} which is for binding
     * an {@link HttpService} fluently.
     */
    @Override
    public VirtualHostContextPathServiceBindingBuilder route() {
        return new VirtualHostContextPathServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link VirtualHostContextPathDecoratingBindingBuilder} which is for binding
     * a {@code decorator} fluently.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public VirtualHostContextPathDecoratingBindingBuilder routeDecorator() {
        return new VirtualHostContextPathDecoratingBindingBuilder(this);
    }

    @Override
    public VirtualHostContextPathServicesBuilder annotatedService(
            String pathPrefix,
            Object service,
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
    public VirtualHostContextPathAnnotatedServiceConfigSetters annotatedService() {
        return new VirtualHostContextPathAnnotatedServiceConfigSetters(this);
    }
}
