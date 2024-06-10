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

import java.util.function.Function;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder class for binding a {@code decorator} to a {@link Route} fluently under a set of context paths.
 *
 * <p>Call {@link #build(Function)} or {@link #build(DecoratingHttpServiceFunction)}
 * to build the {@code decorator} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 *
 * sb.virtualHost("example.com")
 *   .contextPath("/v1", "/v2")
 *   .routeDecorator()                                // Configure a decorator with route.
 *   .pathPrefix("/api/users")
 *   .build((delegate, ctx, req) -> {
 *       if (!"bearer my_token".equals(req.headers().get(HttpHeaderNames.AUTHORIZATION))) {
 *           return HttpResponse.of(HttpStatus.UNAUTHORIZED);
 *       }
 *       return delegate.serve(ctx, req);
 *   });                                              // Return to the VirtualHostBuilder.
 * }</pre>
 */
@UnstableApi
public final class VirtualHostContextPathDecoratingBindingBuilder
        extends AbstractContextPathDecoratingBindingBuilder<VirtualHostContextPathDecoratingBindingBuilder,
        VirtualHostContextPathServicesBuilder> {

    VirtualHostContextPathDecoratingBindingBuilder(VirtualHostContextPathServicesBuilder builder) {
        super(builder);
    }
}
