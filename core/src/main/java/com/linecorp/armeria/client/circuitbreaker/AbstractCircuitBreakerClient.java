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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link Client} decorator that handles failures of remote invocation based on circuit breaker pattern.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractCircuitBreakerClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCircuitBreakerClient.class);

    private final CircuitBreakerMapping mapping;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractCircuitBreakerClient(Client<I, O> delegate, CircuitBreakerMapping mapping) {
        super(delegate);
        this.mapping = requireNonNull(mapping, "mapping");
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return unwrap().execute(ctx, req);
        }

        if (circuitBreaker.tryRequest()) {
            return doExecute(ctx, req, circuitBreaker);
        } else {
            // the circuit is tripped; raise an exception without delegating.
            throw new FailFastException(circuitBreaker);
        }
    }

    /**
     * Invoked when the {@link CircuitBreaker} is in closed state.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req, CircuitBreaker circuitBreaker)
            throws Exception;

    /**
     * Reports a success or a failure to the specified {@link CircuitBreaker} according to the completed value
     * of the specified {@code future}. If the completed value is {@link CircuitBreakerDecision#ignore()},
     * this doesn't do anything.
     *
     * @deprecated Do not use this method.
     */
    @Deprecated
    protected static void reportSuccessOrFailure(CircuitBreaker circuitBreaker,
                                                 CompletionStage<CircuitBreakerDecision> future) {
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
}
