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
 * {@link ServerBuilder#route()}. You can also configure an {@link HttpService} using
 * {@link ServerBuilder#withRoute(Consumer)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 *
 * sb.route()                                      // Configure the first service.
 *   .post("/foo/bar")
 *   .consumes(JSON, PLAIN_TEXT_UTF_8)
 *   .produces(JSON_UTF_8)
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .verboseResponses(true)
 *   .build((ctx, req) -> HttpResponse.of(OK));    // Return to the ServerBuilder.
 *
 * // Configure the second service with Consumer.
 * sb.withRoute(builder -> builder.path("/baz")
 *                                .methods(HttpMethod.GET, HttpMethod.POST)
 *                                .build((ctx, req) -> HttpResponse.of(OK)));
 * }</pre>
 *
 * @see VirtualHostServiceBindingBuilder
 */
public final class ServiceBindingBuilder extends AbstractServiceBindingBuilder<ServiceBindingBuilder> {

    private final ServerBuilder serverBuilder;

    ServiceBindingBuilder(ServerBuilder serverBuilder) {
        super(EMPTY_CONTEXT_PATHS);
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    /**
     * Sets the {@link HttpService} and returns the {@link ServerBuilder} that this
     * {@link ServiceBindingBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link HttpService} will be bound to is not specified
     */
    public ServerBuilder build(HttpService service) {
        requireNonNull(service, "service");
        build0(service);
        return serverBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serverBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
