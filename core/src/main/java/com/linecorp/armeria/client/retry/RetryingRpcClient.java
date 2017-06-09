/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.channel.EventLoop;

/**
 * A {@link Client} decorator that handles failures of an invocation and retries RPC requests.
 */
public class RetryingRpcClient extends RetryingClient<RpcRequest, RpcResponse> {
    private static final RetryException RETRY_EXCEPTION = Exceptions.clearTrace(new RetryException());

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryRequestStrategy<RpcRequest, RpcResponse> retryRequestStrategy) {
        return delegate -> new RetryingRpcClient(delegate, retryRequestStrategy,
                                                 Backoff::withoutDelay);
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryRequestStrategy<RpcRequest, RpcResponse> retryRequestStrategy,
                 Supplier<? extends Backoff> backoffSupplier) {
        return delegate -> new RetryingRpcClient(delegate, retryRequestStrategy, backoffSupplier);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    public RetryingRpcClient(Client<RpcRequest, RpcResponse> delegate,
                             RetryRequestStrategy<RpcRequest, RpcResponse> retryStrategy,
                             Supplier<? extends Backoff> backoffSupplier) {
        super(delegate, retryStrategy, backoffSupplier);
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        DefaultRpcResponse responseFuture = new DefaultRpcResponse();
        Backoff backoff = newBackoff();
        retry(1, backoff, ctx, req, () -> {
            try {
                return delegate().execute(ctx, req);
            } catch (Exception e) {
                return new DefaultRpcResponse(e);
            }
        }, responseFuture);
        return responseFuture;
    }

    private void retry(int currentAttemptNo, Backoff backoff, ClientRequestContext ctx, RpcRequest req,
                       Supplier<RpcResponse> action, DefaultRpcResponse responseFuture) {
        RpcResponse response = action.get();
        response.handle(voidFunction((result, thrown) -> {
            final Throwable exception;
            if (thrown != null) {
                if (!retryStrategy().shouldRetry(req, thrown)) {
                    responseFuture.completeExceptionally(thrown);
                    return;
                }
                exception = thrown;
            } else if (retryStrategy().shouldRetry(req, response)) {
                exception = RETRY_EXCEPTION;
            } else {
                responseFuture.complete(result);
                return;
            }

            long nextInterval = backoff.nextIntervalMillis(currentAttemptNo);
            if (nextInterval < 0) {
                responseFuture.completeExceptionally(exception);
            } else {
                EventLoop eventLoop = ctx.contextAwareEventLoop();
                if (nextInterval <= 0) {
                    eventLoop.submit(() -> retry(currentAttemptNo + 1, backoff, ctx, req, action,
                                                 responseFuture));
                } else {
                    eventLoop.schedule(
                            () -> retry(currentAttemptNo + 1, backoff, ctx, req, action, responseFuture),
                            nextInterval, TimeUnit.MILLISECONDS);
                }
            }
        }));
    }

    private static final class RetryException extends RuntimeException {
        private static final long serialVersionUID = -3816065469543230534L;
    }
}
