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
package com.linecorp.armeria.server.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to circuit break.
 */
public abstract class AbstractCircuitBreakerService<I extends Request, O extends Response>
        extends SimpleDecoratingService<I, O> {

    @Nullable
    private final CircuitBreakerRule rule;

    private final CircuitBreakerServiceHandler handler;

    @Nullable
    private final BiFunction<? super ServiceRequestContext, ? super I, ? extends O> fallback;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected AbstractCircuitBreakerService(
            Service<I, O> delegate, CircuitBreakerRule rule,
            CircuitBreakerServiceHandler handler,
            @Nullable BiFunction<? super ServiceRequestContext, ? super I, ? extends O> fallback) {
        super(delegate);
        this.rule = rule;
        this.handler = requireNonNull(handler, "handler");
        this.fallback = fallback;
    }

    /**
     * Invoked when the {@link CircuitBreaker} is in closed state.
     */
    protected abstract O doServe(ServiceRequestContext ctx, I req, CircuitBreakerCallback callback)
            throws Exception;

    @Override
    public final O serve(ServiceRequestContext ctx, I req) throws Exception {
        System.out.println("Hello!");
        try {
            final CircuitBreakerCallback callback = handler.tryRequest(ctx, req);
            if (callback == null) {
                return unwrap().serve(ctx, req);
            }
            return doServe(ctx, req, callback);
        } catch (Exception ex) {
            if (fallback != null && handler.isCircuitBreakerException(ex)) {
                final O res = fallback.apply(ctx, req);
                return requireNonNull(res, "fallback.apply() returned null.");
            }
            throw ex;
        }
    }
}
