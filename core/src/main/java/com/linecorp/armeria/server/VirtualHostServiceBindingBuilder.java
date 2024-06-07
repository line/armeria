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

import java.util.function.Consumer;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link VirtualHostBuilder#route()}. You can also configure an {@link HttpService} using
 * {@link VirtualHostBuilder#withRoute(Consumer)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return to the
 * {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.virtualHost("example.com")
 *   .route()                                      // Configure the first service in "example.com".
 *   .post("/foo/bar")
 *   .consumes(JSON, PLAIN_TEXT_UTF_8)
 *   .produces(JSON_UTF_8)
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .verboseResponses(true)
 *   .build((ctx, req) -> HttpResponse.of(OK));    // Return to the VirtualHostBuilder.
 *
 * sb.virtualHost("example2.com")                  // Configure the second service "example2.com".
 *   .withRoute(builder -> builder.path("/baz")
 *                                .methods(HttpMethod.GET, HttpMethod.POST)
 *                                .build((ctx, req) -> HttpResponse.of(OK)));
 * }</pre>
 *
 * @see ServiceBindingBuilder
 */
public final class VirtualHostServiceBindingBuilder
        extends AbstractServiceBindingBuilder<VirtualHostServiceBindingBuilder> {

    private final VirtualHostBuilder virtualHostBuilder;

    VirtualHostServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        super(EMPTY_CONTEXT_PATHS);
        this.virtualHostBuilder = requireNonNull(virtualHostBuilder, "virtualHostBuilder");
    }

    /**
     * Sets the {@link HttpService} and returns the {@link VirtualHostBuilder} that this
     * {@link VirtualHostServiceBindingBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link HttpService} will be bound to is not specified
     */
    public VirtualHostBuilder build(HttpService service) {
        requireNonNull(service, "service");
        build0(service);
        return virtualHostBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        virtualHostBuilder.addServiceConfigSetters(serviceConfigBuilder);
    }
}
