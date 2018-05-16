/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

import io.netty.channel.EventLoop;

/**
 * A {@link Client} decorator that handles failures of an invocation and retries RPC requests.
 */
public final class RetryingRpcClient extends RetryingClient<RpcRequest, RpcResponse> {

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     *
     * @param retryStrategy the retry strategy
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy) {
        return new RetryingRpcClientBuilder(retryStrategy).newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy, int maxTotalAttempts) {
        return new RetryingRpcClientBuilder(retryStrategy).maxTotalAttempts(maxTotalAttempts)
                                                          .newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy,
                 int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return new RetryingRpcClientBuilder(retryStrategy)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingRpcClient(Client<RpcRequest, RpcResponse> delegate,
                      RetryStrategy<RpcRequest, RpcResponse> retryStrategy,
                      int totalMaxAttempts, long responseTimeoutMillisForEachAttempt) {
        super(delegate, retryStrategy, totalMaxAttempts, responseTimeoutMillisForEachAttempt);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final DefaultRpcResponse responseFuture = new DefaultRpcResponse();
        doExecute0(ctx, req, responseFuture);
        return responseFuture;
    }

    private void doExecute0(ClientRequestContext ctx, RpcRequest req, DefaultRpcResponse responseFuture) {
        if (!setResponseTimeout(ctx)) {
            completeOnException(ctx, responseFuture, ResponseTimeoutException.get());
            return;
        }

        final RpcResponse response = getResponse(ctx, req);

        retryStrategy().shouldRetry(req, response).handle(voidFunction((backoff, unused) -> {
            if (backoff != null) {
                final long nextDelay;
                try {
                    nextDelay = getNextDelay(ctx, backoff);
                } catch (Exception e) {
                    completeOnException(ctx, responseFuture, e);
                    return;
                }

                final EventLoop eventLoop = ctx.contextAwareEventLoop();
                if (nextDelay <= 0) {
                    eventLoop.execute(() -> doExecute0(ctx, req, responseFuture));
                } else {
                    eventLoop.schedule(() -> doExecute0(ctx, req, responseFuture),
                                       nextDelay, TimeUnit.MILLISECONDS);
                }
            } else {
                response.handle(voidFunction((result, thrown) -> {
                    if (thrown != null) {
                        completeOnException(ctx, responseFuture, thrown);
                    } else {
                        onRetryingComplete(ctx);
                        responseFuture.complete(result);
                    }
                }));
            }
        }));
    }

    private static void completeOnException(ClientRequestContext ctx, DefaultRpcResponse responseFuture,
                                            Throwable thrown) {
        onRetryingComplete(ctx);
        responseFuture.completeExceptionally(thrown);
    }

    private RpcResponse getResponse(ClientRequestContext ctx, RpcRequest req) {
        try {
            return executeDelegate(ctx, req);
        } catch (Exception e) {
            return new DefaultRpcResponse(e);
        }
    }
}
