/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.kotlin;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.kotlin.CoroutineContextProvider;
import com.linecorp.armeria.common.kotlin.CoroutineContexts;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to configure the coroutine context which is used as an initial context
 * of annotated services' suspending functions.
 *
 * <p>Example:
 * <pre>{@code
 * > serverBuilder
 * >     .annotatedService(object {
 * >         @Get("/users/{uid}")
 * >         suspend fun foo(@Param("uid") uid: String): HttpResponse {
 * >             ...
 * >         }
 * >     })
 * >     .decorator(CoroutineContextService.newDecorator { ctx ->
 * >         CoroutineName(CoroutineName(ctx.config().defaultServiceNaming.serviceName(ctx) ?: "name"))
 * >     })
 * }
 * </pre>
 *
 * <p>Note that {@code ctx.eventLoop()} is used as coroutine dispatcher by default,
 * and {@code ctx.blockingTaskExecutor()} is used if `useBlockingTaskExecutor` is set to true
 * or methods are annotated with {@code @Blocking}.
 */
public final class CoroutineContextService extends SimpleDecoratingHttpService {

    /**
     * Returns a new {@link HttpService} decorator that injects into annotated services the coroutine context
     * provided by the specified {@code provider}.
     */
    public static Function<? super HttpService, CoroutineContextService> newDecorator(
            CoroutineContextProvider provider) {
        return delegate -> new CoroutineContextService(delegate, provider);
    }

    private final CoroutineContextProvider provider;

    CoroutineContextService(HttpService delegate, CoroutineContextProvider provider) {
        super(requireNonNull(delegate, "delegate"));
        this.provider = requireNonNull(provider, "provider");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        CoroutineContexts.set(ctx, requireNonNull(provider.provide(ctx), "provider returned null"));
        return unwrap().serve(ctx, req);
    }
}
