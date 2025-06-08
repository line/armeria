/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
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
        super(delegate, mapping, null);
    }

    @Override
    protected ResponseFuturePair<RpcResponse> getResponseFuturePair(
            ClientRequestContext ctx) {
        final CompletableFuture<RpcResponse> responseFuture = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(responseFuture);
        return new ResponseFuturePair<>(res, responseFuture);
    }

    @Override
    protected void doExecute(ClientRequestContext ctx, RpcRequest req, RpcResponse res,
                             CompletableFuture<RpcResponse> returnedResFuture) throws Exception {
        doExecute0(ctx, req, res, returnedResFuture);
    }

    private void doExecute0(ClientRequestContext ctx, RpcRequest req,
                            RpcResponse returnedRes, CompletableFuture<RpcResponse> returnedResFuture) {
        final int totalAttempts = getTotalAttempts(ctx);
        final boolean initialAttempt = totalAttempts <= 1;
        if (returnedRes.isDone()) {
            // The response has been cancelled by the client before it receives a response, so stop retrying.
            handleException(ctx, returnedResFuture, new CancellationException(
                    "the response returned to the client has been cancelled"), initialAttempt);
            return;
        }
        if (!updateResponseTimeout(ctx)) {
            handleException(ctx, returnedResFuture, ResponseTimeoutException.get(), initialAttempt);
            return;
        }

        final ClientRequestContext attemptCtx = newAttemptContext(ctx, null, req, initialAttempt);

        if (!initialAttempt) {
            attemptCtx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ARMERIA_RETRY_COUNT, StringUtil.toString(totalAttempts - 1)));
        }

        final RpcResponse attemptRes;

        final ClientRequestContextExtension ctxExtension = attemptCtx.as(ClientRequestContextExtension.class);
        final EndpointGroup endpointGroup = attemptCtx.endpointGroup();
        if (!initialAttempt && ctxExtension != null &&
            endpointGroup != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected, try to initialize the ctx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(unwrap(), ctxExtension, RpcResponse::from,
                                                           (context, cause) -> RpcResponse.ofFailure(cause),
                                                           req, true);
        } else {
            attemptRes = executeWithFallback(unwrap(), attemptCtx,
                                             (context, cause) -> RpcResponse.ofFailure(cause),
                                             req, true);
        }

        onAttemptStarted(ctx, attemptCtx, (@Nullable Throwable cause) -> {
            attemptCtx.cancel();
        });

        final RetryConfig<RpcResponse> retryConfig = mappedRetryConfig(ctx);
        final RetryRuleWithContent<RpcResponse> retryRule =
                retryConfig.needsContentInRule() ?
                retryConfig.retryRuleWithContent() : retryConfig.fromRetryRule();
        attemptRes.handle((unused1, cause) -> {
            try {
                assert retryRule != null;
                retryRule.shouldRetry(attemptCtx, attemptRes, cause).handle((decision, unused3) -> {
                    final Backoff backoff = decision != null ? decision.backoff() : null;

                    if (backoff != null) {
                        final RetrySchedulabilityDecision schedulabilityDecision = canScheduleWith(ctx,
                                                                                                   backoff);

                        if (schedulabilityDecision.canSchedule()) {
                            scheduleNextRetry(ctx,
                                              () -> doExecute0(ctx, req, returnedRes, returnedResFuture),
                                              schedulabilityDecision.nextRetryTimeNanos(),
                                              backoff,
                                              cause0 -> handleException(ctx, returnedResFuture, cause0, false));
                        }
                    }

                    final boolean isOtherAttemptInProgress = onAttemptEnded(ctx);

                    if (!isOtherAttemptInProgress) {
                        onRetryingComplete(ctx, returnedResFuture, attemptCtx, attemptRes);
                    }

                    return null;
                });
            } catch (Throwable t) {
                handleException(ctx, returnedResFuture, t, false);
            }
            return null;
        });

        final long hedgingDelayMillis = retryConfig.hedgingDelayMillis();

        if (hedgingDelayMillis >= 0) {
            final Backoff hedgingBackoff = Backoff.fixed(hedgingDelayMillis);
            final RetrySchedulabilityDecision schedulabilityDecision =
                    canScheduleWith(ctx, hedgingBackoff);
            if (schedulabilityDecision.canSchedule()) {
                scheduleNextRetry(ctx, () -> doExecute0(ctx, req, returnedRes, returnedResFuture),
                                  schedulabilityDecision.nextRetryTimeNanos(),
                                  hedgingBackoff,
                                  cause -> handleException(ctx, returnedResFuture, cause, false));
            }
        }
    }

    private static void handleException(ClientRequestContext ctx,
                                        CompletableFuture<RpcResponse> returnedResFuture,
                                        Throwable cause, boolean endRequestLog) {
        if (isRetryingComplete(ctx)) {
            return;
        }

        returnedResFuture.completeExceptionally(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        onRetryingCompleteExceptionally(ctx, cause);
    }

    void onRetryingComplete(ClientRequestContext ctx,
                            CompletableFuture<RpcResponse> returnedResFuture,
                            ClientRequestContext attemptCtx,
                            RpcResponse attemptRes) {
        if (isRetryingComplete(ctx)) {
            return;
        }

        final HttpRequest actualHttpReq = attemptCtx.request();
        if (actualHttpReq != null) {
            ctx.updateRequest(actualHttpReq);
        }

        returnedResFuture.complete(attemptRes);
        onRetryingComplete(ctx, attemptCtx);
    }
}
