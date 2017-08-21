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

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

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
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy) {
        return new RetryingRpcClientBuilder(retryStrategy).newDecorator();
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     */
    public static Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient>
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy, int defaultMaxAttempts) {
        return new RetryingRpcClientBuilder(retryStrategy).defaultMaxAttempts(defaultMaxAttempts)
                                                          .newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingRpcClient(Client<RpcRequest, RpcResponse> delegate,
                      RetryStrategy<RpcRequest, RpcResponse> retryStrategy, int defaultMaxAttempts) {
        super(delegate, retryStrategy, defaultMaxAttempts);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final DefaultRpcResponse responseFuture = new DefaultRpcResponse();
        retry(ctx, req, () -> {
            try {
                resetResponseTimeout(ctx);
                return delegate().execute(ctx, req);
            } catch (Exception e) {
                return new DefaultRpcResponse(e);
            }
        }, responseFuture);
        return responseFuture;
    }

    private void retry(ClientRequestContext ctx, RpcRequest req, Supplier<RpcResponse> action,
                       DefaultRpcResponse responseFuture) {
        RpcResponse response = action.get();
        retryStrategy().shouldRetry(req, response).handle(voidFunction((backoffOpt, unused) -> {
            if (backoffOpt.isPresent()) {
                retry0(ctx, backoffOpt.get(), req, action, responseFuture);
            } else {
                response.handle(voidFunction((result, thrown) -> {
                    if (thrown != null) {
                        final Optional<Backoff> backoffOnExceptionOpt =
                                retryStrategy().shouldRetry(req, thrown);
                        if (backoffOnExceptionOpt.isPresent()) {
                            retry0(ctx, backoffOnExceptionOpt.get(), req, action, responseFuture);
                        } else { // exception that is not for retry occurred
                            responseFuture.completeExceptionally(thrown);
                        }
                    } else { // normal response
                        responseFuture.complete(result);
                    }
                }));
            }
        }));
    }

    private void retry0(ClientRequestContext ctx, Backoff backoff, RpcRequest req,
                        Supplier<RpcResponse> action, DefaultRpcResponse responseFuture) {
        long nextDelay;
        try {
            nextDelay = getNextDelay(ctx, backoff);
            if (nextDelay < 0) { // exceed the number of max attempt
                responseFuture.completeExceptionally(RetryGiveUpException.get());
                return;
            }
        } catch (ResponseTimeoutException e) {
            responseFuture.completeExceptionally(e);
            return;
        }

        EventLoop eventLoop = ctx.contextAwareEventLoop();
        if (nextDelay <= 0) {
            eventLoop.submit(() -> retry(ctx, req, action, responseFuture));
        } else {
            eventLoop.schedule(() -> retry(ctx, req, action, responseFuture), nextDelay, TimeUnit.MILLISECONDS);
        }
    }
}
