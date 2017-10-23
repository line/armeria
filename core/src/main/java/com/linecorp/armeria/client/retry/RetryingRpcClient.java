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
     * @param defaultMaxAttempts the default number of max attempts for retry
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy, int defaultMaxAttempts) {
        return new RetryingRpcClientBuilder(retryStrategy).defaultMaxAttempts(defaultMaxAttempts)
                                                          .newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     *
     * @param retryStrategy the retry strategy
     * @param defaultMaxAttempts the default number of max attempts for retry
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy,
                 int defaultMaxAttempts, long responseTimeoutMillisForEachAttempt) {
        return new RetryingRpcClientBuilder(retryStrategy)
                .defaultMaxAttempts(defaultMaxAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingRpcClient(Client<RpcRequest, RpcResponse> delegate,
                      RetryStrategy<RpcRequest, RpcResponse> retryStrategy,
                      int defaultMaxAttempts, long responseTimeoutMillisForEachAttempt) {
        super(delegate, retryStrategy, defaultMaxAttempts, responseTimeoutMillisForEachAttempt);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final DefaultRpcResponse responseFuture = new DefaultRpcResponse();
        doExecute0(ctx, req, responseFuture);
        return responseFuture;
    }

    private void doExecute0(ClientRequestContext ctx, RpcRequest req, DefaultRpcResponse responseFuture) {
        if (!setResponseTimeout(ctx)) {
            responseFuture.completeExceptionally(ResponseTimeoutException.get());
            return;
        }
        final RpcResponse response = executeDelegate(ctx, req);

        retryStrategy().shouldRetry(req, response).handle(voidFunction((backoffOpt, unused) -> {
            if (backoffOpt.isPresent()) {
                long nextDelay;
                try {
                    nextDelay = getNextDelay(ctx, backoffOpt.get());
                } catch (Exception e) {
                    responseFuture.completeExceptionally(e);
                    return;
                }

                EventLoop eventLoop = ctx.contextAwareEventLoop();
                if (nextDelay <= 0) {
                    eventLoop.execute(() -> doExecute0(ctx, req, responseFuture));
                } else {
                    eventLoop.schedule(() -> doExecute0(ctx, req, responseFuture),
                                       nextDelay, TimeUnit.MILLISECONDS);
                }
            } else {
                response.handle(voidFunction((result, thrown) -> {
                    if (thrown == null) {
                        // normal response
                        responseFuture.complete(result);
                    } else {
                        // failed
                        responseFuture.completeExceptionally(thrown);
                    }
                }));
            }
        }));
    }

    private RpcResponse executeDelegate(ClientRequestContext ctx, RpcRequest req) {
        try {
            return delegate().execute(ctx, req);
        } catch (Exception e) {
            return new DefaultRpcResponse(e);
        }
    }
}
