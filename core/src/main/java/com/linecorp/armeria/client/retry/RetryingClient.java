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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * An {@link HttpClient} decorator that handles failures of an invocation and retries HTTP requests.
 */
public final class RetryingClient extends AbstractRetryingClient<HttpRequest, HttpResponse>
        implements HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(RetryingClient.class);

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfig}.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static RetryingClientBuilder builder(RetryConfig<HttpResponse> retryConfig) {
        return new RetryingClientBuilder(retryConfig);
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRule}.
     */
    public static RetryingClientBuilder builder(RetryRule retryRule) {
        return new RetryingClientBuilder(RetryConfig.<HttpResponse>builder0(retryRule).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return new RetryingClientBuilder(RetryConfig.builder0(retryRuleWithContent).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent} and
     * the specified {@code maxContentLength}.
     * The {@code maxContentLength} required to determine whether to retry or not. If the total length of
     * content exceeds this length and there's no retry condition matched,
     * it will hand over the stream to the client.
     *
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent,
                                                int maxContentLength) {
        checkArgument(maxContentLength > 0, "maxContentLength: %s (expected: > 0)", maxContentLength);
        return new RetryingClientBuilder(
                RetryConfig.builder0(retryRuleWithContent).maxContentLength(maxContentLength).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfigMapping}.
     */
    public static RetryingClientBuilder builderWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return new RetryingClientBuilder(mapping);
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     */
    public static Function<? super HttpClient, RetryingClient> newDecorator(RetryRule retryRule) {
        return builder(retryRule).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return builder(retryRuleWithContent).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts) {
        return builder(retryRuleWithContent).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts)
                                 .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                                 .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts,
                 long responseTimeoutMillisForEachAttempt) {
        return builder(retryRuleWithContent)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryConfig<HttpResponse> retryConfig) {
        return builder(retryConfig).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param mapping the mapping that returns a {@link RetryConfig} for a given {@link ClientRequestContext}
     *        and {@link Request}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecoratorWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return builderWithMapping(mapping).newDecorator();
    }

    private final boolean useRetryAfter;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    RetryingClient(
            HttpClient delegate,
            RetryConfigMapping<HttpResponse> mapping,
            @Nullable RetryConfig<HttpResponse> retryConfig,
            boolean useRetryAfter) {
        super(delegate, mapping, retryConfig);
        this.useRetryAfter = useRetryAfter;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> resFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(resFuture, ctx.eventLoop());
        final RetryingContext rctx = new RetryingContext(ctx, mappedRetryConfig(ctx), res, resFuture, req);
        rctx.init().handle((initSuccessful, unused) -> {
            if (!initSuccessful) {
                return null;
            }

            final RetryAttempt attempt = new RetryAttempt(rctx, unwrap(), 1);
            doExecuteAttempt(rctx, attempt);

            return null;
        });
        return res;
    }

    private void doExecuteAttempt(RetryingContext rctx, RetryAttempt attempt) {
        if (rctx.isCompleted()) {
            // The request or response has been aborted by the client before it receives a response,
            // so stop retrying.
            attempt.abort();
            return;
        }

        attempt.execute().handle((unused, unexpectedAttemptCause) -> {
            if (unexpectedAttemptCause != null) {
                assert attempt.state() == RetryAttempt.State.ABORTED;
                rctx.abort(unexpectedAttemptCause);
                return null;
            }

            assert attempt.state() == RetryAttempt.State.COMPLETED;

            decideOnAttempt(rctx, attempt).handle((nextDelayMillis, unexpectedDecisionCause) -> {
                if (unexpectedDecisionCause != null) {
                    attempt.abort(unexpectedDecisionCause);
                    assert attempt.state() == RetryAttempt.State.ABORTED;
                    rctx.abort(unexpectedDecisionCause);
                    return null;
                }

                assert attempt.state() == RetryAttempt.State.COMPLETED;

                if (nextDelayMillis >= 0) {
                    attempt.abort();
                    assert attempt.state() == RetryAttempt.State.ABORTED;

                    scheduleNextRetry(
                            rctx.ctx(), rctx::abort,
                            () -> {
                                final RetryAttempt nextAttempt = new RetryAttempt(
                                            rctx, unwrap(), getTotalAttempts(rctx.ctx()));
                                doExecuteAttempt(rctx, nextAttempt);
                            },
                            nextDelayMillis);
                    return null;
                }

                rctx.commit(attempt);
                assert attempt.state() == RetryAttempt.State.COMMITTED;
                return null;
            });

            return null;
        });
    }

    private CompletionStage<Long> decideOnAttempt(RetryingContext rctx, RetryAttempt attempt) {
        assert attempt.state() == RetryAttempt.State.COMPLETED;

        try {
            return attempt.shouldRetry().handle((decision, cause) -> {
                assert cause == null;
                assert attempt.state() == RetryAttempt.State.COMPLETED;
                final Backoff backoff = decision != null ? decision.backoff() : null;
                if (backoff != null) {
                    final long millisAfter = useRetryAfter ? attempt.retryAfterMillis() : -1;
                    final long nextDelay = getNextDelay(rctx.ctx(), backoff, millisAfter);
                    if (nextDelay >= 0) {
                        return nextDelay;
                    }
                }

                return -1L;
            });
        } catch (Throwable t) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(t);
        }
    }
}
