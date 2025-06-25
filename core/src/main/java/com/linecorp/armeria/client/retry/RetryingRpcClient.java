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

import com.linecorp.armeria.client.ClientFactory;
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
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final CompletableFuture<RpcResponse> returnedResFuture = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(returnedResFuture);
        doExecute0(ctx, req, res, returnedResFuture, 1);
        return res;
    }

    private void doExecute0(ClientRequestContext ctx, RpcRequest req,
                            RpcResponse returnedRes, CompletableFuture<RpcResponse> returnedResFuture,
                            int attemptNo) {
        final boolean initialAttempt = attemptNo <= 1;
        if (returnedRes.isDone()) {
            // The response has been cancelled by the client before it receives a response, so stop retrying.
            completeRetryingExceptionally(ctx, returnedResFuture, new CancellationException(
                    "the response returned to the client has been cancelled"), initialAttempt);
            return;
        }
        if (!setResponseTimeout(ctx)) {
            completeRetryingExceptionally(ctx, returnedResFuture, ResponseTimeoutException.get(),
                                          initialAttempt);
            return;
        }

        final ClientRequestContext attemptCtx = newDerivedContext(ctx, null, req, initialAttempt);

        if (!initialAttempt) {
            attemptCtx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ARMERIA_RETRY_COUNT, StringUtil.toString(attemptNo - 1)));
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

        startRetryAttempt(ctx, attemptCtx, attemptRes, (winningAttemptCtx, winningAttemptRes) -> {
            winningAttemptCtx.eventLoop().execute(() -> {
                ctx.logBuilder().endResponseWithChild(winningAttemptCtx.log());
                final HttpRequest actualHttpReq = winningAttemptCtx.request();
                if (actualHttpReq != null) {
                    ctx.updateRequest(actualHttpReq);
                }

                returnedResFuture.complete(winningAttemptRes);
            });
        }, (abortingAttemptCtx,
            abortingAttemptRes,
            cause) -> {
            final CompletableFuture<Void> abortComplete = new CompletableFuture<>();

            abortingAttemptCtx.eventLoop().execute(() -> {
                if (cause != null) {
                    abortingAttemptCtx.cancel(cause);
                } else {
                    abortingAttemptCtx.cancel();
                }

                abortingAttemptRes.toCompletableFuture().handle((unused, unusedCause) -> {
                    abortComplete.complete(null);
                    return null;
                });
            });

            return abortComplete;
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
                        scheduleNextRetry(ctx,
                                          nextAttemptNo -> doExecute0(ctx, req, returnedRes,
                                                                      returnedResFuture, nextAttemptNo),
                                          backoff,
                                          cause0 -> handleExceptionAfterScheduling(ctx, returnedResFuture,
                                                                                   cause0));
                    }

                    completeRetryAttempt(ctx, attemptCtx, attemptRes, backoff == null);
                    return null;
                });
            } catch (Throwable t) {
                completeRetryingExceptionally(ctx, returnedResFuture, t, false);
            }
            return null;
        });

        final long hedgingDelayMillis = retryConfig.hedgingDelayMillis();

        if (hedgingDelayMillis >= 0) {
            final Backoff hedgingBackoff = Backoff.fixed(hedgingDelayMillis);
            scheduleNextRetry(ctx, hedgingAttemptNo ->
                                      doExecute0(ctx, req, returnedRes, returnedResFuture,
                                                 hedgingAttemptNo),
                              hedgingBackoff,
                              cause -> handleExceptionAfterScheduling(ctx, returnedResFuture, cause));
        }
    }

    private static void handleExceptionAfterScheduling(
            ClientRequestContext ctx, CompletableFuture<RpcResponse> returnedResFuture, Throwable cause) {
        if (cause instanceof RetrySchedulingException) {
            switch (((RetrySchedulingException) cause).getType()) {
                case RETRYING_ALREADY_COMPLETED:
                    return;
                case NO_MORE_ATTEMPTS_IN_RETRY:
                case NO_MORE_ATTEMPTS_IN_BACKOFF:
                case DELAY_FROM_BACKOFF_EXCEEDS_RESPONSE_TIMEOUT:
                case DELAY_FROM_SERVER_EXCEEDS_RESPONSE_TIMEOUT:
                case RETRY_TASK_OVERTAKEN:
                    completeRetryingIfNoPendingAttempts(ctx);
                    return;
                case RETRY_TASK_CANCELLED:
                    cause = new IllegalStateException(
                            ClientFactory.class.getSimpleName() + " has been closed.", cause);
                    break;
            }
        }
        completeRetryingExceptionally(ctx, returnedResFuture, cause, false);
    }

    private static void completeRetryingExceptionally(ClientRequestContext ctx,
                                                      CompletableFuture<RpcResponse> returnedResFuture,
                                                      Throwable cause, boolean endRequestLog) {
        if (isRetryingComplete(ctx)) {
            return;
        }

        returnedResFuture.completeExceptionally(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);

        completeRetryingExceptionally(ctx, cause);
    }
}
