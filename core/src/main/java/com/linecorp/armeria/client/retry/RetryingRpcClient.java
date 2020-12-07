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

import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * An {@link RpcClient} decorator that handles failures of an invocation and retries RPC requests.
 */
public final class RetryingRpcClient extends AbstractRetryingClient<RpcRequest, RpcResponse>
        implements RpcClient {

    /**
     * Creates a new {@link RpcClient} decorator that handles failures of an invocation and retries
     * RPC requests.
     *
     * @param retryRuleWithContent the retry rule
     */
    public static Function<? super RpcClient, RetryingRpcClient>
    newDecorator(RetryRuleWithContent<RpcResponse> retryRuleWithContent) {
        return builder(retryRuleWithContent).newDecorator();
    }

    /**
     * Creates a new {@link RpcClient} decorator that handles failures of an invocation and retries
     * RPC requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super RpcClient, RetryingRpcClient>
    newDecorator(RetryRuleWithContent<RpcResponse> retryRuleWithContent, int maxTotalAttempts) {
        return builder(retryRuleWithContent).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link RpcClient} decorator that handles failures of an invocation and retries
     * RPC requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super RpcClient, RetryingRpcClient>
    newDecorator(RetryRuleWithContent<RpcResponse> retryRuleWithContent,
                 int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return builder(retryRuleWithContent)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                .newDecorator();
    }

    /**
     * Creates a new {@link RpcClient} decorator that handles failures of an invocation and retries
     * RPC requests.
     * The {@link RetryConfig} object encapsulates {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static Function<? super RpcClient, RetryingRpcClient>
    newDecorator(RetryConfig<RpcResponse> retryConfig) {
        return builder(retryConfig).newDecorator();
    }

    /**
     * Creates a new {@link RpcClient} decorator that handles failures of an invocation and retries
     * RPC requests.
     *
     * @param mapping the mapping that returns a {@link RetryConfig} for a given {@link ClientRequestContext}
     *        and {@link Request}.
     */
    public static Function<? super RpcClient, RetryingRpcClient>
    newDecorator(RetryConfigMapping<RpcResponse> mapping) {
        return builder(mapping).newDecorator();
    }

    /**
     * Returns a new {@link RetryingRpcClientBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryingRpcClientBuilder builder(RetryRuleWithContent<RpcResponse> retryRuleWithContent) {
        return new RetryingRpcClientBuilder(RetryConfig.builder0(retryRuleWithContent).build());
    }

    /**
     * Returns a new {@link RetryingRpcClientBuilder} with the specified {@link RetryConfig}.
     * The {@link RetryConfig} encapsulates {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static RetryingRpcClientBuilder builder(RetryConfig<RpcResponse> retryConfig) {
        return new RetryingRpcClientBuilder(retryConfig);
    }

    /**
     * Returns a new {@link RetryingRpcClientBuilder} with the specified {@link RetryConfigMapping}.
     */
    public static RetryingRpcClientBuilder builder(RetryConfigMapping<RpcResponse> mapping) {
        return new RetryingRpcClientBuilder(mapping);
    }

    /**
     * Creates a new instance that decorates the specified {@link RpcClient}.
     */
    RetryingRpcClient(RpcClient delegate, RetryConfigMapping<RpcResponse> mapping) {
        super(delegate, mapping, null);
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
        final int totalAttempts = getTotalAttempts(ctx);
        final boolean initialAttempt = totalAttempts <= 1;
        if (returnedRes.isDone()) {
            // The response has been cancelled by the client before it receives a response, so stop retrying.
            handleException(ctx, future, new CancellationException(
                    "the response returned to the client has been cancelled"), initialAttempt);
            return;
        }
        if (!setResponseTimeout(ctx)) {
            handleException(ctx, future, ResponseTimeoutException.get(), initialAttempt);
            return;
        }

        final ClientRequestContext derivedCtx = newDerivedContext(ctx, null, req, initialAttempt);
        ctx.logBuilder().addChild(derivedCtx.log());

        if (!initialAttempt) {
            derivedCtx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ARMERIA_RETRY_COUNT, Integer.toString(totalAttempts - 1)));
        }

        final RpcResponse res = executeWithFallback(unwrap(), derivedCtx,
                                                    (context, cause) -> RpcResponse.ofFailure(cause));

        final RetryConfig<RpcResponse> retryConfig = mapping().get(ctx, req);
        final RetryRuleWithContent<RpcResponse> retryRule =
                retryConfig.needsContentInRule() ?
                retryConfig.retryRuleWithContent() : retryConfig.fromRetryRule();
        res.handle((unused1, cause) -> {
            try {
                retryRule.shouldRetry(derivedCtx, res, cause).handle((decision, unused3) -> {
                    final Backoff backoff = decision != null ? decision.backoff() : null;
                    if (backoff != null) {
                        final long nextDelay = getNextDelay(derivedCtx, backoff);
                        if (nextDelay < 0) {
                            onRetryComplete(ctx, derivedCtx, res, future);
                            return null;
                        }

                        scheduleNextRetry(ctx, cause0 -> handleException(ctx, future, cause0, false),
                                          () -> doExecute0(ctx, req, returnedRes, future), nextDelay);
                    } else {
                        onRetryComplete(ctx, derivedCtx, res, future);
                    }
                    return null;
                });
            } catch (Throwable t) {
                handleException(ctx, future, t, false);
            }
            return null;
        });
    }

    private static void onRetryComplete(ClientRequestContext ctx, ClientRequestContext derivedCtx,
                                        RpcResponse res, CompletableFuture<RpcResponse> future) {
        onRetryingComplete(ctx);
        final HttpRequest actualHttpReq = derivedCtx.request();
        if (actualHttpReq != null) {
            ctx.updateRequest(actualHttpReq);
        }
        future.complete(res);
    }

    private static void handleException(ClientRequestContext ctx, CompletableFuture<RpcResponse> future,
                                        Throwable cause, boolean endRequestLog) {
        future.completeExceptionally(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
    }
}
