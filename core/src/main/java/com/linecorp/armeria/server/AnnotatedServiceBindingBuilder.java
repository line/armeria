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

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link ServerBuilder#annotatedService()}.
 *
 * <p>Call {@link #build(Object)} to build the {@link HttpService} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.annotatedService()                       // Returns an instance of this class
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
 *   .pathPrefix("/foo")
 *   .verboseResponses(true)
 *   .build(new Service())                     // Return to the ServerBuilder.
 *   .build();
 * }</pre>
 *
 * @see ServiceBindingBuilder
 */
public final class AnnotatedServiceBindingBuilder
        extends AbstractAnnotatedServiceConfigSetters<AnnotatedServiceBindingBuilder> {

    private final ServerBuilder serverBuilder;

    AnnotatedServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    /**
     * Registers the given service to {@link ServerBuilder} and return {@link ServerBuilder}
     * to continue building {@link Server}.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     * @return {@link ServerBuilder} to continue building {@link Server}
     */
    public ServerBuilder build(Object service) {
        service(service);
        serverBuilder.annotatedServiceBindingBuilder(this);
        return serverBuilder;
    }
}
