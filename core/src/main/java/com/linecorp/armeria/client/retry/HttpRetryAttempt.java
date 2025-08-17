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

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.netty.handler.codec.DateFormatter;

class HttpRetryAttempt implements RetryAttempt<HttpResponse> {
    private static final Logger logger = LoggerFactory.getLogger(HttpRetryAttempt.class);

    enum State {
        // Initial state after constructing an `Attempt`.
        // The attempt response is underway but did not complete yet.
        EXECUTING,
        // todo(szymon): doc
        DECIDING,
        // todo(szymon): doc
        DECIDED,
        // State after a call to `commit`. Terminal state, caller cannot make further calls.
        COMMITTED,
        // State after a call to `abort`. Terminal state, caller cannot make further calls.
        // `res` is aborted.
        ABORTED
    }

    private State state;

    private final HttpRetryingContext rctx;
    private final ClientRequestContext ctx;
    private HttpResponse res;
    @Nullable
    private Throwable resCause;
    @Nullable
    private HttpResponse resWithContent;
    private final CompletableFuture<@Nullable RetryDecision> whenDecidedFuture;

    HttpRetryAttempt(HttpRetryingContext rctx, ClientRequestContext ctx, HttpResponse res,
                     boolean isResponseStreaming) {
        this.rctx = rctx;
        this.ctx = ctx;
        this.res = res;
        resCause = null;
        resWithContent = null;
        whenDecidedFuture = new CompletableFuture<>();

        state = State.EXECUTING;

        if (!isResponseStreaming || rctx.config().requiresResponseTrailers()) {
            handleAggRes();
        } else {
            handleStreamingRes();
        }
    }

    private void decide() {
        assert state == State.EXECUTING;
        state = State.DECIDING;

        final CompletionStage<@Nullable RetryDecision> retryRuleDecisionFuture;
        if (rctx.config().needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                    rctx.config().retryRuleWithContent();
            assert retryRuleWithContent != null;
            retryRuleDecisionFuture = shouldBeRetriedWith(retryRuleWithContent);
        } else {
            final RetryRule retryRule = rctx.config().retryRule();
            assert retryRule != null;
            retryRuleDecisionFuture = shouldBeRetriedWith(retryRule);
        }

        retryRuleDecisionFuture.whenComplete((result, exception) -> {
            state = State.DECIDED;
            if (exception != null) {
                whenDecidedFuture.completeExceptionally(exception);
            } else {
                whenDecidedFuture.complete(result);
            }
        });
    }

    public HttpResponse commit() {
        if (state == State.COMMITTED) {
            return res;
        }

        assert state == State.DECIDED;
        state = State.COMMITTED;

        if (resWithContent != null) {
            resWithContent.abort();
        }

        return res;
    }

    public void abort() {
        abort(AbortedStreamException.get());
    }

    public void abort(Throwable cause) {
        if (state == State.ABORTED || state == State.COMMITTED) {
            return;
        }

        assert state == State.EXECUTING || state == State.DECIDED;
        state = State.ABORTED;

        if (resWithContent != null) {
            resWithContent.abort();
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        // Set response content with null to make sure that the log is complete.
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);
        res.abort(cause);

        whenDecidedFuture.completeExceptionally(cause);
    }

    @Override
    public CompletableFuture<@Nullable RetryDecision> whenDecided() {
        return whenDecidedFuture;
    }

    ClientRequestContext ctx() {
        return ctx;
    }

    State state() {
        return state;
    }

    long retryAfterMillis() {
        assert state == State.DECIDED;

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

    private void handleAggRes() {
        assert state == State.EXECUTING;

        res.aggregate().handle((aggRes, resCause) -> {
            assert state == State.EXECUTING;

            if (resCause != null) {
                ctx.logBuilder().endRequest(resCause);
                ctx.logBuilder().endResponse(resCause);
                complete(HttpResponse.ofFailure(resCause), resCause);
            } else {
                completeLogIfBytesNotTransferred(aggRes);
                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                    completeWithContent(aggRes.toHttpResponse(), aggRes.toHttpResponse());
                });
            }
            return null;
        });
    }

    private void handleStreamingRes() {
        assert state == State.EXECUTING;

        final SplitHttpResponse splitRes = res.split();
        splitRes.headers().handle((resHeaders, headersCause) -> {
            assert state == State.EXECUTING;

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

                if (rctx.config().needsContentInRule() && resCause == null) {
                    final HttpResponse unsplitRes = splitRes.unsplit();
                    final HttpResponseDuplicator resDuplicator =
                            unsplitRes.toDuplicator(ctx.eventLoop().withoutContext(),
                                                    ctx.maxResponseLength());
                    // todo(szymon): We do not call duplicator.abort(cause); but res.abort on an exception.
                    //   Is this okay?
                    final HttpResponse duplicatedRes = resDuplicator.duplicate();
                    final TruncatingHttpResponse truncatingAttemptRes =
                            new TruncatingHttpResponse(resDuplicator.duplicate(),
                                                       rctx.config().maxContentLength());
                    resDuplicator.close();
                    completeWithContent(duplicatedRes, truncatingAttemptRes);
                } else {
                    if (resCause != null) {
                        splitRes.body().abort(resCause);
                        complete(HttpResponse.ofFailure(resCause), resCause);
                    } else {
                        complete(splitRes.unsplit(), null);
                    }
                }
            });
            return null;
        });
    }

    private void completeLogIfBytesNotTransferred(AggregatedHttpResponse aggRes) {
        assert state == State.EXECUTING;

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

    private void completeWithContent(HttpResponse res, HttpResponse resWithContent) {
        assert state == State.EXECUTING;

        this.res = res;
        this.resWithContent = resWithContent;
        resCause = null;
        decide();
    }

    private void complete(HttpResponse res, @Nullable Throwable resCause) {
        assert state == State.EXECUTING;

        if (resCause != null) {
            resCause = Exceptions.peel(resCause);
        }

        this.res = res;
        resWithContent = null;
        this.resCause = resCause;
        decide();
    }

    private CompletionStage<@Nullable RetryDecision> shouldBeRetriedWith(RetryRule retryRule) {
        assert state == State.DECIDING;

        try {
            return retryRule.shouldRetry(ctx, resCause);
        } catch (Throwable t) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(t);
        }
    }

    private CompletionStage<@Nullable RetryDecision> shouldBeRetriedWith(
            RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        assert state == State.DECIDING;

        @Nullable
        final HttpResponse resForRule;
        @Nullable
        final Throwable causeForRule;

        if (resCause != null) {
            resForRule = null;
            causeForRule = resCause;
        } else {
            if (resWithContent == null) {
                resForRule = res;
            } else {
                resForRule = resWithContent;
            }

            causeForRule = null;
        }

        try {
            return retryRuleWithContent.shouldRetry(ctx, resForRule, causeForRule);
        } catch (Throwable t) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(t);
        }
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("state", state)
                .add("rctx", rctx)
                .add("ctx", ctx)
                .add("res", res)
                .add("resCause", resCause)
                .add("resWithContent", resWithContent)
                .toString();
    }
}
