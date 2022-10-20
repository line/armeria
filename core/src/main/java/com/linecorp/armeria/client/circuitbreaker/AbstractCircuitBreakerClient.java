/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import java.util.concurrent.CompletionStage;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link Client} decorator that handles failures of remote invocation based on circuit breaker pattern.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractCircuitBreakerClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private class HandlerGenerator<CB> {

        CircuitBreakerClientHandlerFactory<CB, I> factory;
        ClientCircuitBreakerGenerator<CB> mapping;

        HandlerGenerator(CircuitBreakerClientHandlerFactory<CB, I> factory,
                         ClientCircuitBreakerGenerator<CB> mapping) {
            this.factory = factory;
            this.mapping = mapping;
        }

        CircuitBreakerClientHandler<I> acquire() {
            return factory.generateHandler(mapping);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("factory", factory)
                              .add("mapping", mapping)
                              .toString();
        }
    }

    private final HandlerGenerator<?> handlerGenerator;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    <CB> AbstractCircuitBreakerClient(Client<I, O> delegate, CircuitBreakerClientHandlerFactory<CB, I> factory,
                                      ClientCircuitBreakerGenerator<CB> mapping) {
        super(delegate);
        handlerGenerator = new HandlerGenerator<>(factory, mapping);
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final CircuitBreakerClientHandler<I> handler = handlerGenerator.acquire();
        try {
            handler.tryAcquireAndRequest(ctx, req);
        } catch (CircuitBreakerAbortException e) {
            return unwrap().execute(ctx, req);
        }
        return doExecute(ctx, req, handler);
    }

    /**
     * Invoked when the {@link CircuitBreaker} is in closed state.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req,
                                   CircuitBreakerClientHandler<I> handler) throws Exception;

    /**
     * Reports a success or a failure to the specified {@link CircuitBreaker} according to the completed value
     * of the specified {@code future}. If the completed value is {@link CircuitBreakerDecision#ignore()},
     * this doesn't do anything.
     *
     * @deprecated Do not use this method.
     */
    @Deprecated
    protected static void reportSuccessOrFailure(CircuitBreaker circuitBreaker,
                                                 CompletionStage<@Nullable CircuitBreakerDecision> future) {
        future.handle((decision, unused) -> {
            if (decision != null) {
                if (decision == CircuitBreakerDecision.success() || decision == CircuitBreakerDecision.next()) {
                    circuitBreaker.onSuccess();
                } else if (decision == CircuitBreakerDecision.failure()) {
                    circuitBreaker.onFailure();
                } else {
                    // Ignore, does not count as a success nor failure.
                }
            }
            return null;
        }).exceptionally(CompletionActions::log);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("handlerGenerator", handlerGenerator)
                          .toString();
    }
}
