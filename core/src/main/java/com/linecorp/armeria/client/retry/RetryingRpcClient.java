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

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

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
        final CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(future);
        doExecute0(ctx, req, future);
        return res;
    }

    private void doExecute0(ClientRequestContext ctx, RpcRequest req, CompletableFuture<RpcResponse> future) {
        if (!setResponseTimeout(ctx)) {
            onRetryingComplete(ctx);
            future.completeExceptionally(ResponseTimeoutException.get());
            return;
        }

        final RpcResponse res = getResponse(ctx, req);
        res.whenComplete((unused1, unused2) -> {
            retryStrategy().shouldRetry(req, res).whenComplete((backoff, unused3) -> {
                if (backoff != null) {
                    final long nextDelay = getNextDelay(ctx, backoff);
                    if (nextDelay < 0) {
                        onRetryingComplete(ctx);
                        future.complete(res);
                        return;
                    }

                    final Consumer<? super Throwable> actionOnException = cause -> {
                        onRetryingComplete(ctx);
                        future.completeExceptionally(cause);
                    };
                    final Runnable retryTask = () -> doExecute0(ctx, req, future);
                    scheduleNextRetry(ctx, actionOnException, retryTask, nextDelay);
                } else {
                    onRetryingComplete(ctx);
                    future.complete(res);
                }
            });
        });
    }

    private RpcResponse getResponse(ClientRequestContext ctx, RpcRequest req) {
        try {
            return executeDelegate(ctx, req);
        } catch (Exception e) {
            return new DefaultRpcResponse(e);
        }
    }
}
