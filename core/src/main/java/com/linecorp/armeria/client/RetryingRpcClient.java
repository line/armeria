/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

import io.netty.channel.EventLoop;

/**
 * A {@link Client} decorator that handles failures of an invocation and retries RPC requests.
 */
public class RetryingRpcClient extends RetryingClient<RpcRequest, RpcResponse> {
    private final RetryRequestStrategy<? super RpcRequest, Object> retryStrategy;

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     */
    public static Function<Client<? super RpcRequest, ? extends RpcResponse>, RetryingRpcClient>
    newDecorator(int retries) {
        return delegate -> new RetryingRpcClient(delegate,
                                                 RetryRequestStrategy.alwaysFalse(),
                                                 retries,
                                                 Backoff::withoutDelay);
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     */
    public static Function<Client<? super RpcRequest, ? extends RpcResponse>, RetryingRpcClient>
    newDecorator(int retries,
                 RetryRequestStrategy<? super RpcRequest, Object> retryRequestStrategy) {
        return delegate -> new RetryingRpcClient(delegate, retryRequestStrategy, retries,
                                                 Backoff::withoutDelay);
    }

    /**
     * Creates a new {@link Client} decorator that handles failures of an invocation and retries RPC requests.
     */
    public static Function<Client<? super RpcRequest, ? extends RpcResponse>, RetryingRpcClient>
    newDecorator(int retries,
                 RetryRequestStrategy<? super RpcRequest, Object> retryRequestStrategy,
                 Supplier<? extends Backoff> backoffSupplier) {
        return delegate -> new RetryingRpcClient(delegate, retryRequestStrategy, retries, backoffSupplier);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    private RetryingRpcClient(Client<? super RpcRequest, ? extends RpcResponse> delegate,
                              RetryRequestStrategy<? super RpcRequest, Object> retryStrategy,
                              int retries, Supplier<? extends Backoff> backoffSupplier) {
        super(delegate, retries, backoffSupplier);
        this.retryStrategy = requireNonNull(retryStrategy, "retryStrategy");
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        DefaultRpcResponse responseFuture = new DefaultRpcResponse();
        retry(maxRetries(), newBackoff(), ctx, req, () -> {
            try {
                return delegate().execute(ctx, req);
            } catch (Exception e) {
                return new DefaultRpcResponse(e);
            }
        }, responseFuture);
        return responseFuture;
    }

    private void retry(int times, Backoff backoff, ClientRequestContext ctx, RpcRequest req,
                       Supplier<RpcResponse> action, DefaultRpcResponse responseFuture) {
        RpcResponse response = action.get();
        response.thenAccept(result -> {
            if (retryStrategy.shouldRetry(req, result)) {
                throw new RuntimeException("need to retry request");
            }
            responseFuture.complete(result);
        }).exceptionally(voidFunction(thrown -> {
            if (times <= 0) {
                responseFuture.completeExceptionally(thrown);
            } else {
                long nextInterval = backoff.nextIntervalMillis();
                EventLoop eventLoop = ctx.eventLoop().next();
                if (nextInterval <= 0) {
                    eventLoop.submit(() -> retry(times - 1, backoff, ctx, req, action, responseFuture));
                } else {
                    eventLoop.schedule(
                            () -> retry(times - 1, backoff, ctx, req, action, responseFuture),
                            nextInterval, TimeUnit.MILLISECONDS);
                }
            }
        }));
    }
}
