/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.brave;

import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.brave.TraceContextUtil;
import com.linecorp.armeria.server.brave.BraveService;

import brave.Span;
import brave.propagation.TraceContext;

/**
 * Manually propagates a {@link TraceContext} to a {@link BraveClient}.
 */
@UnstableApi
public final class TraceContextPropagation {

    /**
     * Injects the current {@link TraceContext} through {@link ClientBuilder#contextCustomizer(Consumer)} or
     * {@link Clients#withContextCustomizer(Consumer)}. The injected {@link TraceContext} will be propagated
     * to {@link BraveClient} as a parent {@link Span}.
     *
     * <p>Basically, a parent {@link TraceContext} is automatically propagated to {@link BraveClient} if you use
     * {@link BraveService}. This method is useful if you want to manually propagate the current
     * {@link TraceContext} to {@link BraveClient} in non-Armeria server environment.
     * <pre>{@code
     * Tracing threadLocalTracing = ...;
     * Tracing requestContextTracing =
     *     Tracing.newBuilder()
     *            .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
     *            .build();
     *
     * Clients.builder(...)
     *        .contextCustomizer(TraceContextPropagation.inject(() -> {
     *            return threadLocalTracing.currentTraceContext().get();
     *        })
     *        .decorator(BraveClient.newDecorator(requestContextTracing))
     *        .build();
     * }</pre>
     */
    @UnstableApi
    public static Consumer<ClientRequestContext> inject(Supplier<TraceContext> traceContextSupplier) {
        requireNonNull(traceContextSupplier, "traceContextSupplier");
        return ctx -> {
            final TraceContext traceContext = traceContextSupplier.get();
            requireNonNull(traceContext, "traceContextSupplier.get() returned null");
            TraceContextUtil.setTraceContext(ctx, traceContext);
        };
    }

    private TraceContextPropagation() {}
}
