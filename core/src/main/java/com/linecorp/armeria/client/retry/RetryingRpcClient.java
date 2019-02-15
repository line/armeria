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

import static com.linecorp.armeria.internal.ClientUtil.executeWithFallback;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
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
     * @param retryStrategyWithContent the retry strategy
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategyWithContent<RpcResponse> retryStrategyWithContent) {
        return new RetryingRpcClientBuilder(retryStrategyWithContent).newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     *
     * @param retryStrategyWithContent the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategyWithContent<RpcResponse> retryStrategyWithContent, int maxTotalAttempts) {
        return new RetryingRpcClientBuilder(retryStrategyWithContent).maxTotalAttempts(maxTotalAttempts)
                                                                     .newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     *
     * @param retryStrategyWithContent the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategyWithContent<RpcResponse> retryStrategyWithContent,
                 int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return new RetryingRpcClientBuilder(retryStrategyWithContent)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingRpcClient(Client<RpcRequest, RpcResponse> delegate,
                      RetryStrategyWithContent<RpcResponse> retryStrategyWithContent,
                      int totalMaxAttempts, long responseTimeoutMillisForEachAttempt) {
        super(delegate, retryStrategyWithContent, totalMaxAttempts, responseTimeoutMillisForEachAttempt);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(future);
        doExecute0(ctx, req, res, future);
        return res;
    }

    private void doExecute0(ClientRequestContext ctx, RpcRequest req,
                            RpcResponse returnedRes, CompletableFuture<RpcResponse> future) {
        if (returnedRes.isDone()) {
            // The response has been cancelled by the client before it receives a response, so stop retrying.
            handleException(ctx, future, new CancellationException(
                    "the response returned to the client has been cancelled"));
            return;
        }
        if (!setResponseTimeout(ctx)) {
            handleException(ctx, future, ResponseTimeoutException.get());
            return;
        }

        final ClientRequestContext derivedCtx = ctx.newDerivedContext(req);
        ctx.logBuilder().addChild(derivedCtx.log());
        final BiFunction<ClientRequestContext, Throwable, RpcResponse> fallback =
                (context, cause) -> new DefaultRpcResponse(cause);

        final int totalAttempts = getTotalAttempts(ctx);
        if (totalAttempts > 1) {
            derivedCtx.setAdditionalRequestHeader(ARMERIA_RETRY_COUNT, Integer.toString(totalAttempts - 1));
        }

        final RpcResponse res = executeWithFallback(delegate(), derivedCtx, req, fallback);

        res.handle((unused1, unused2) -> {
            retryStrategyWithContent().shouldRetry(derivedCtx, res).handle((backoff, unused3) -> {
                if (backoff != null) {
                    final long nextDelay = getNextDelay(derivedCtx, backoff);
                    if (nextDelay < 0) {
                        onRetryingComplete(ctx);
                        future.complete(res);
                        return null;
                    }

                    scheduleNextRetry(ctx, cause -> handleException(ctx, future, cause),
                                      () -> doExecute0(ctx, req, returnedRes, future), nextDelay);
                } else {
                    onRetryingComplete(ctx);
                    future.complete(res);
                }
                return null;
            });
            return null;
        });
    }

    private static void handleException(ClientRequestContext ctx, CompletableFuture<RpcResponse> future,
                                        Throwable cause) {
        onRetryingComplete(ctx);
        future.completeExceptionally(cause);
    }
}
