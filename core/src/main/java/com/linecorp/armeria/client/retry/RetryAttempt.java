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

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static com.linecorp.armeria.client.retry.AbstractRetryingClient.newAttemptContext;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.netty.handler.codec.DateFormatter;

class RetryAttempt {
    private static final Logger logger = LoggerFactory.getLogger(RetryAttempt.class);

    enum State {
        // State of the attempt after construction and before the call to execute() or abort().
        INITIALIZED,
        // State of the attempt before the outer call to execute() and before the inner call to complete()
        // or abort().
        EXECUTING,
        // State of the attempt before the call to commit() and before the outer call to commit() or abort()
        // It means that this attempt ended up with a response, with or without content depending on the
        // RetryRule, and it is up to the caller to decide on whether to commit on this response (i.e.,
        // returning this response from this client) or to abort to continue retrying with
        // another attempt.
        COMPLETED,
        // After the outer call to commit(). Terminal state.
        COMMITTED,
        ABORTED
        // After the outer call to abort() or after an unexpected exception. Terminal state.
        // After setting this state, all attempt-related resources are freed.
    }

    private State state;

    private final CompletableFuture<Void> whenCompletedFuture;

    RetryingContext rctx;

    final int number;

    // Available only in Attempt.State.EXECUTING.
    @Nullable
    private ClientRequestContext ctx;
    @Nullable
    private HttpResponse res;

    // Available only after Attempt.State.COMPLETED.
    @Nullable
    private HttpResponse completedRes;
    @Nullable
    private Throwable completedResCause;

    Client<HttpRequest, HttpResponse> delegate;

    RetryAttempt(RetryingContext rctx, Client<HttpRequest, HttpResponse> delegate, int number) {
        this.rctx = rctx;
        this.delegate = delegate;
        this.number = number;
        whenCompletedFuture = new CompletableFuture<>();
        state = State.INITIALIZED;
    }

    int number() {
        return number;
    }

    State state() {
        return state;
    }

    CompletableFuture<Void> execute() {
        assert state == State.INITIALIZED;
        final boolean isInitialAttempt = number <= 1;

        final HttpRequest req;
        if (isInitialAttempt) {
            req = rctx.reqDuplicator().duplicate();
        } else {
            final RequestHeadersBuilder attemptHeadersBuilder = rctx.req().headers().toBuilder();
            attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, number - 1);
            req = rctx.reqDuplicator().duplicate(attemptHeadersBuilder.build());
        }

        try {
            ctx = newAttemptContext(
                    rctx.ctx(), req, rctx.ctx().rpcRequest(), isInitialAttempt);
        } catch (Throwable t) {
            abort(t);
            return whenCompletedFuture;
        }

        executeAttemptRequest();
        assert state == State.EXECUTING && res != null && ctx != null;

        if (!rctx.ctx().exchangeType().isResponseStreaming() || rctx.config().requiresResponseTrailers()) {
            handleAggRes();
        } else {
            handleStreamingRes();
        }

        return whenCompletedFuture;
    }

    CompletionStage<@Nullable RetryDecision> shouldRetry() {
        assert state == State.COMPLETED;
        assert ctx != null;

        if (rctx.config().needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                    rctx.config().retryRuleWithContent();
            assert retryRuleWithContent != null;
            return shouldBeRetriedWith(retryRuleWithContent);
        } else {
            final RetryRule retryRule = rctx.config().retryRule();
            assert retryRule != null;
            return shouldBeRetriedWith(retryRule);
        }
    }

    long retryAfterMillis() {
        assert state == State.COMPLETED;
        assert ctx != null;

        final RequestLogAccess attemptLog = ctx.log();
        final String retryAfterValue;
        final RequestLog requestLog = attemptLog.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
        retryAfterValue = requestLog != null ?
                          requestLog.responseHeaders().get(HttpHeaderNames.RETRY_AFTER) : null;

        if (retryAfterValue != null) {
            try {
                return Duration.ofSeconds(Integer.parseInt(retryAfterValue)).toMillis();
            } catch (Exception ignored) {
                // Not a second value.
            }

            try {
                @SuppressWarnings("UseOfObsoleteDateTimeApi")
                final Date retryAfterDate = DateFormatter.parseHttpDate(retryAfterValue);
                if (retryAfterDate != null) {
                    return retryAfterDate.getTime() - System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // `parseHttpDate()` can raise an exception rather than returning `null`
                // when the given value has more than 64 characters.
            }

            logger.debug("The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                         retryAfterValue);
        }

        return -1;
    }

    HttpResponse commit() {
        if (state == State.COMMITTED) {
            assert res != null;
            return res;
        }

        assert res != null;

        assert state == State.COMPLETED;
        state = State.COMMITTED;

        if (completedRes != null) {
            completedRes.abort();
        }

        return res;
    }

    void abort() {
        abort(AbortedStreamException.get());
    }

    void abort(Throwable cause) {
        if (state == State.ABORTED) {
            return;
        }
        assert state == State.EXECUTING || state == State.COMPLETED;
        assert ctx != null && res != null;
        state = State.ABORTED;

        if (completedRes != null) {
            completedRes.abort();
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        // Set response content with null to make sure that the log is complete.
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);
        res.abort(cause);

        if (!whenCompletedFuture.isDone()) {
            whenCompletedFuture.completeExceptionally(cause);
        }
    }

    private void executeAttemptRequest() {
        assert state == State.INITIALIZED;
        assert ctx != null;
        final HttpRequest req = ctx.request();
        assert req != null;
        final ClientRequestContextExtension ctxExtension =
                ctx.as(ClientRequestContextExtension.class);
        if ((number > 1) && ctxExtension != null && ctx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(ctx);
            // if the endpoint hasn't been selected,
            // try to initialize the ctx with a new endpoint/event loop
            res = initContextAndExecuteWithFallback(
                    delegate, ctxExtension, HttpResponse::of,
                    (context, cause) ->
                            HttpResponse.ofFailure(cause), req, false);
        } else {
            res = executeWithFallback(delegate, ctx,
                                      (context, cause) ->
                                              HttpResponse.ofFailure(cause), req, false);
        }

        state = State.EXECUTING;
    }

    private void handleAggRes() {
        assert state == State.EXECUTING;
        assert ctx != null && res != null;

        res.aggregate().handle((aggRes, resCause) -> {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

            if (resCause != null) {
                ctx.logBuilder().endRequest(resCause);
                ctx.logBuilder().endResponse(resCause);
                complete(HttpResponse.ofFailure(resCause), resCause);
            } else {
                completeLogIfBytesNotTransferred(aggRes);
                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                    complete(aggRes.toHttpResponse(), null);
                });
            }
            return null;
        });
    }

    private void handleStreamingRes() {
        assert state == State.EXECUTING;
        assert ctx != null && res != null;

        final SplitHttpResponse splitRes = res.split();
        splitRes.headers().handle((resHeaders, headersCause) -> {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

            final Throwable resCause;
            if (headersCause == null) {
                final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                resCause = log != null ? log.responseCause() : null;
            } else {
                resCause = Exceptions.peel(headersCause);
            }
            completeLogIfBytesNotTransferred(resHeaders, resCause);

            ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {
                assert state == State.EXECUTING;
                assert ctx != null && res != null;

                if (rctx.config().needsContentInRule() && resCause == null) {
                    final HttpResponse unsplitRes = splitRes.unsplit();
                    final HttpResponseDuplicator resDuplicator =
                            unsplitRes.toDuplicator(ctx.eventLoop().withoutContext(),
                                                    ctx.maxResponseLength());
                    try {
                        res = resDuplicator.duplicate();
                        final TruncatingHttpResponse truncatingAttemptRes =
                                new TruncatingHttpResponse(resDuplicator.duplicate(),
                                                           rctx.config().maxContentLength());
                        resDuplicator.close();
                        complete(truncatingAttemptRes, null);
                    } catch (Throwable cause) {
                        resDuplicator.abort(cause);
                        abort(cause);
                    }
                } else {
                    final HttpResponse unsplitRes;
                    if (resCause != null) {
                        splitRes.body().abort(resCause);
                        unsplitRes = HttpResponse.ofFailure(resCause);
                    } else {
                        unsplitRes = splitRes.unsplit();
                    }

                    complete(unsplitRes, resCause);
                }
            });
            return null;
        });
    }

    private void completeLogIfBytesNotTransferred(AggregatedHttpResponse aggRes) {
        assert state == State.EXECUTING;
        assert ctx != null && res != null;

        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder attemptLogBuilder = ctx.logBuilder();
            attemptLogBuilder.endRequest();
            attemptLogBuilder.responseHeaders(aggRes.headers());
            if (!aggRes.trailers().isEmpty()) {
                attemptLogBuilder.responseTrailers(aggRes.trailers());
            }
            attemptLogBuilder.endResponse();
        }
    }

    private void completeLogIfBytesNotTransferred(
            @Nullable ResponseHeaders headers,
            @Nullable Throwable resCause) {
        assert state == State.EXECUTING;
        assert ctx != null && res != null;

        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (resCause != null) {
                logBuilder.endRequest(resCause);
                logBuilder.endResponse(resCause);
            } else {
                logBuilder.endRequest();
                if (headers != null) {
                    logBuilder.responseHeaders(headers);
                }
                res.whenComplete().handle((unused, cause) -> {
                    if (cause != null) {
                        logBuilder.endResponse(cause);
                    } else {
                        logBuilder.endResponse();
                    }
                    return null;
                });
            }
        }
    }

    private void complete(@Nullable HttpResponse resToCompleteWith,
                          @Nullable Throwable causeToCompleteWith) {
        assert state == State.EXECUTING;
        assert ctx != null && res != null;
        state = State.COMPLETED;

        if (causeToCompleteWith != null) {
            causeToCompleteWith = Exceptions.peel(causeToCompleteWith);
        }

        completedRes = resToCompleteWith;
        completedResCause = causeToCompleteWith;
        whenCompletedFuture.complete(null);
    }

    private CompletionStage<@Nullable RetryDecision> shouldBeRetriedWith(RetryRule retryRule) {
        assert state == State.COMPLETED;
        assert ctx != null;

        return retryRule.shouldRetry(ctx, completedResCause)
                        .handle((decision, cause) -> {
                            if (cause != null) {
                                logger.warn("Unexpected exception is raised from {}.",
                                            retryRule, cause);
                                return null;
                            }
                            return decision;
                        });
    }

    private CompletionStage<@Nullable RetryDecision> shouldBeRetriedWith(
            RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        assert state == State.COMPLETED;
        assert ctx != null;

        return retryRuleWithContent.shouldRetry(ctx, completedRes, completedResCause)
                                   .handle((decision, cause) -> {
                                       if (cause != null) {
                                           logger.warn("Unexpected exception is raised from {}.",
                                                       retryRuleWithContent, cause);
                                           return null;
                                       }
                                       return decision;
                                   });
    }
}
