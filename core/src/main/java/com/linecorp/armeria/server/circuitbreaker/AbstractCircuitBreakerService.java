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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to circuit break.
 */
public abstract class AbstractCircuitBreakerService<I extends Request, O extends Response>
        extends SimpleDecoratingService<I, O> {

    private final CircuitBreaker circuitBreaker;
    private final Function<CompletionStage<? extends O>, O> responseConverter;
    private final CircuitBreakerAcceptHandler<I, O> acceptHandler;
    private final CircuitBreakerRejectHandler<I, O> rejectHandler;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected AbstractCircuitBreakerService(Service<I, O> delegate, CircuitBreaker circuitBreaker,
                                            Function<CompletionStage<? extends O>, O> responseConverter,
                                            CircuitBreakerAcceptHandler<I, O> acceptHandler,
                                            CircuitBreakerRejectHandler<I, O> rejectHandler) {
        super(delegate);
        this.circuitBreaker = requireNonNull(circuitBreaker, "circuitBreaker");
        this.responseConverter = requireNonNull(responseConverter, "responseConverter");
        this.acceptHandler = requireNonNull(acceptHandler, "acceptHandler");
        this.rejectHandler = requireNonNull(rejectHandler, "rejectHandler");
    }

    @Override
    public final O serve(ServiceRequestContext ctx, I req) throws Exception {
        return responseConverter.apply(
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                if (circuitBreaker.tryRequest()) {
                                    return acceptHandler.handleAccepted(unwrap(), ctx, req);
                                } else {
                                    return rejectHandler.handleRejected(unwrap(), ctx, req);
                                }
                            } catch (Exception e) {
                                return Exceptions.throwUnsafely(e);
                            }
                        },
                        ctx.eventLoop()
                )
        );
    }
}
