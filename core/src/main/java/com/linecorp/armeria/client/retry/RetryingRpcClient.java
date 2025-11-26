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
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.common.util.StringUtil;

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
        super(delegate, mapping);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req, RetryConfig<RpcResponse> config)
            throws Exception {
        final CompletableFuture<RpcResponse> resFuture = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(resFuture);
        final RetryContext<RpcRequest, RpcResponse> rctx =
                new RetryContext<>(ctx, req, res, resFuture, config, ctx.responseTimeoutMillis());
        rctx.counter().consumeAttemptFrom(null);
        retry(rctx);
        return res;
    }

    private void retry(RetryContext<RpcRequest, RpcResponse> rctx) {
        final int totalAttempts = rctx.counter().numberAttemptsSoFar();
        final boolean initialAttempt = totalAttempts <= 1;
        if (rctx.ctx().isCancelled() || rctx.res().isDone()) {
            // The response has been cancelled by the client before it receives a response, so stop retrying.
            handleException(rctx, new CancellationException(
                    "the response returned to the client has been cancelled"), initialAttempt);
            return;
        }
        if (!rctx.setResponseTimeout()) {
            handleException(rctx, ResponseTimeoutException.get(), initialAttempt);
            return;
        }

        final RetryAttempt<RpcResponse> attempt = executeAttempt(rctx);

        final RetryRuleWithContent<RpcResponse> retryRule =
                rctx.config().needsContentInRule() ?
                rctx.config().retryRuleWithContent() : rctx.config().fromRetryRule();
        attempt.res().handle((unused1, cause) -> {
            try {
                assert retryRule != null;
                retryRule.shouldRetry(attempt.ctx(), attempt.res(), cause).handle((decision, unused3) -> {
                    final Backoff backoff = decision != null ? decision.backoff() : null;
                    if (backoff != null) {
                        final long nextDelay = getNextDelay(rctx, backoff, -1);
                        if (nextDelay < 0) {
                            onRetryComplete(rctx, attempt);
                            return null;
                        }

                        scheduleNextRetry(rctx.ctx(), cause0 -> handleException(rctx, cause0, false),
                                          () -> retry(rctx), nextDelay);
                    } else {
                        onRetryComplete(rctx, attempt);
                    }
                    return null;
                });
            } catch (Throwable t) {
                handleException(rctx, t, false);
            }
            return null;
        });
    }

    private RetryAttempt<RpcResponse> executeAttempt(RetryContext<RpcRequest, RpcResponse> rctx) {
        final int totalAttempts = rctx.counter().numberAttemptsSoFar();
        final boolean initialAttempt = totalAttempts <= 1;
        final ClientRequestContext attemptCtx = ClientUtil.newDerivedContext(rctx.ctx(), null, rctx.req(),
                                                                             initialAttempt);

        if (!initialAttempt) {
            attemptCtx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ClientUtil.ARMERIA_RETRY_COUNT,
                                           StringUtil.toString(totalAttempts - 1)));
        }

        final RpcResponse attemptRes;
        final ClientRequestContextExtension attemptCtxExtension = attemptCtx.as(
                ClientRequestContextExtension.class);
        final EndpointGroup endpointGroup = attemptCtx.endpointGroup();
        if (!initialAttempt && attemptCtxExtension != null &&
            endpointGroup != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected, try to initialize the ctx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(unwrap(), attemptCtxExtension, RpcResponse::from,
                                                           (context, cause) ->
                                                                   RpcResponse.ofFailure(cause),
                                                           rctx.req(), true);
        } else {
            attemptRes = executeWithFallback(unwrap(), attemptCtx,
                                             (context, cause) ->
                                                     RpcResponse.ofFailure(cause),
                                             rctx.req(), true);
        }

        return new RetryAttempt<>(attemptCtx, attemptRes);
    }

    private static void onRetryComplete(RetryContext<RpcRequest, RpcResponse> rctx,
                                        RetryAttempt<RpcResponse> attempt) {
        rctx.ctx().logBuilder().endResponseWithLastChild();
        final HttpRequest actualHttpReq = attempt.ctx().request();
        if (actualHttpReq != null) {
            rctx.ctx().updateRequest(actualHttpReq);
        }
        rctx.resFuture().complete(attempt.res());
    }

    private static void handleException(RetryContext<RpcRequest, RpcResponse> rctx,
                                        Throwable cause, boolean endRequestLog) {
        rctx.resFuture().completeExceptionally(cause);
        if (endRequestLog) {
            rctx.ctx().logBuilder().endRequest(cause);
        }
        rctx.ctx().logBuilder().endResponse(cause);
    }
}
