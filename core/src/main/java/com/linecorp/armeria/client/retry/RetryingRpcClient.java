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
    newDecorator(RetryStrategy<RpcRequest, RpcResponse> retryStrategy,
                 Supplier<? extends Backoff> backoffSupplier) {
        return new RetryingRpcClientBuilder(retryStrategy)
                .backoffSupplier(backoffSupplier).newDecorator();
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    RetryingRpcClient(Client<RpcRequest, RpcResponse> delegate,
                      RetryStrategy<RpcRequest, RpcResponse> retryStrategy,
                      Supplier<? extends Backoff> backoffSupplier, int defaultMaxAttempts) {
        super(delegate, retryStrategy, backoffSupplier, defaultMaxAttempts);
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
        retryStrategy().shouldRetry(req, response).handle(voidFunction((retry, unused) -> {
            if (retry != null && retry) {
                retry0(currentAttemptNo, backoff, ctx, req,
                       action, responseFuture, RetryGiveUpException.get());
            } else {
                response.handle(voidFunction((result, thrown) -> {
                    final Throwable exception;
                    if (thrown != null) {
                        if (!retryStrategy().shouldRetry(req, thrown)) {
                            responseFuture.completeExceptionally(thrown);
                            return;
                        }
                        exception = thrown;
                    } else {
                        responseFuture.complete(result);
                        return;
                    }
                    retry0(currentAttemptNo, backoff, ctx, req, action, responseFuture, exception);
                }));
            }
        }));
    }

    private void retry0(int currentAttemptNo, Backoff backoff, ClientRequestContext ctx, RpcRequest req,
                        Supplier<RpcResponse> action, DefaultRpcResponse responseFuture, Throwable exception) {
        long nextDelay = backoff.nextDelayMillis(currentAttemptNo);
        if (nextDelay < 0) {
            responseFuture.completeExceptionally(exception);
        } else {
            EventLoop eventLoop = ctx.contextAwareEventLoop();
            if (nextDelay <= 0) {
                eventLoop.submit(() -> retry(currentAttemptNo + 1, backoff, ctx, req, action,
                                             responseFuture));
            } else {
                eventLoop.schedule(
                        () -> retry(currentAttemptNo + 1, backoff, ctx, req, action, responseFuture),
                        nextDelay, TimeUnit.MILLISECONDS);
            }
        }
    }
}
