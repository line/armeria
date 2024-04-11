/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.spring.client;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestAttribute;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Provides access to {@link RequestAttribute}s specified when sending a request.
 *
 * <p>Example:
 * <pre>{@code
 * interface GreetingService {
 *    @GetExchange("/hello")
 *    Mono<String> hello(@RequestAttribute("requestId") String requestId);
 * }
 *
 * WebClient
 *   .builder("http://example.com")
 *   .decorator((delegate, ctx, req) -> {
 *      final String requestId = RequestAttributeAccess.get(ctx, "requestId");
 *      ctx.addAdditionalRequestHeader("X-Request-Id", requestId);
 *     return delegate.execute(ctx, req);
 *   })
 *   .build();
 *
 * GreetingService greetingService = ...
 * // The request attribute will be set to the "X-Request-Id" header.
 * greetingService.hello("123");
 * }</pre>
 */
@UnstableApi
public final class RequestAttributeAccess {

    private static final AttributeKey<Map<String, Object>> KEY =
            AttributeKey.valueOf(RequestAttributeAccess.class, "ATTRIBUTES_KEY");

    /**
     * Returns the {@link RequestAttribute} value associated with the name.
     */
    @Nullable
    public static Object get(ClientRequestContext ctx, String name) {
        requireNonNull(ctx, "ctx");
        requireNonNull(name, "name");
        final Map<String, Object> attrs = ctx.attr(KEY);
        if (attrs == null) {
            return null;
        }
        return attrs.get(name);
    }

    static void set(RequestOptionsBuilder requestOptionsBuilder, Map<String, Object> attributes) {
        requestOptionsBuilder.attr(KEY, attributes);
    }

    private RequestAttributeAccess() {}
}
