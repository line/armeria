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
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;

class RetryingContext {
    private enum State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        COMPLETING,
        COMPLETED
    }

    private final ClientRequestContext ctx;
    private final RetryConfig<HttpResponse> retryConfig;
    private final HttpResponse res;
    private final CompletableFuture<HttpResponse> resFuture;
    private final HttpRequest req;
    private State state;

    @Nullable
    private HttpRequestDuplicator reqDuplicator;

    RetryingContext(ClientRequestContext ctx,
                    RetryConfig<HttpResponse> retryConfig,
                    HttpResponse res,
                    CompletableFuture<HttpResponse> resFuture,
                    HttpRequest req) {

        this.ctx = ctx;
        this.retryConfig = retryConfig;
        this.res = res;
        this.resFuture = resFuture;
        this.req = req;
        state = State.UNINITIALIZED;
        reqDuplicator = null; // will be initialized in init().
    }

    RetryConfig<HttpResponse> config() {
        return retryConfig;
    }

    ClientRequestContext ctx() {
        return ctx;
    }

    CompletableFuture<Boolean> init() {
        assert state == State.UNINITIALIZED;
        state = State.INITIALIZING;
        final CompletableFuture<Boolean> initFuture = new CompletableFuture<>();

        if (ctx.exchangeType().isRequestStreaming()) {
            reqDuplicator =
                    req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            state = State.INITIALIZED;
            initFuture.complete(true);
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, reqCause) -> {
                   assert state == State.INITIALIZING;

                   if (reqCause != null) {
                       resFuture.completeExceptionally(reqCause);
                       ctx.logBuilder().endRequest(reqCause);
                       ctx.logBuilder().endResponse(reqCause);
                       state = State.COMPLETED;
                       initFuture.complete(false);
                   } else {
                       reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       state = State.INITIALIZED;
                       initFuture.complete(true);
                   }
                   return null;
               });
        }

        return initFuture;
    }

    @Nullable
    public RetryAttempt newRetryAttempt(int attemptNumber, Client<HttpRequest, HttpResponse> delegate) {
        if (isCompleted()) {
            return null;
        }

        assert state == State.INITIALIZED;
        assert reqDuplicator != null;

        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!AbstractRetryingClient.setResponseTimeout(ctx)) {
            throw ResponseTimeoutException.get();
        }

        final HttpRequest attemptReq;
        final ClientRequestContext attemptCtx;
        if (isInitialAttempt) {
            attemptReq = reqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder attemptHeadersBuilder = req.headers().toBuilder();
            attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, attemptNumber - 1);
            attemptReq = reqDuplicator.duplicate(attemptHeadersBuilder.build());
        }

        attemptCtx = AbstractRetryingClient.newAttemptContext(
                ctx, attemptReq, ctx.rpcRequest(), isInitialAttempt);

        final HttpResponse attemptRes;
        final ClientRequestContextExtension attemptCtxExt =
                attemptCtx.as(ClientRequestContextExtension.class);
        if (!isInitialAttempt && attemptCtxExt != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected,
            // try to initialize the attempCtx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(
                    delegate, attemptCtxExt, HttpResponse::of,
                    (context, cause) ->
                            HttpResponse.ofFailure(cause), attemptReq, false);
        } else {
            attemptRes = executeWithFallback(delegate, attemptCtx,
                                       (context, cause) ->
                                               HttpResponse.ofFailure(cause), attemptReq, false);
        }

        return new RetryAttempt(this, attemptCtx, attemptRes);
    }

    void commit(RetryAttempt attempt) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }
        assert attempt.state() == RetryAttempt.State.COMPLETED;
        assert state == State.INITIALIZED;
        assert reqDuplicator != null;
        state = State.COMPLETED;

        final HttpResponse attemptRes = attempt.commit();
        ctx.logBuilder().endResponseWithLastChild();
        resFuture.complete(attemptRes);
        reqDuplicator.close();
    }

    void abort(Throwable cause) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }

        assert state == State.INITIALIZED || state == State.COMPLETING;
        assert reqDuplicator != null;
        state = State.COMPLETED;

        reqDuplicator.abort(cause);

        // todo(szymon): verify that this safe to do so we can avoid isInitialAttempt check
        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }

        ctx.logBuilder().endResponse(cause);

        resFuture.completeExceptionally(cause);
    }

    private boolean isCompleted() {
        if (state == State.COMPLETING || state == State.COMPLETED) {
            return true;
        }

        assert state == State.INITIALIZED;

        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (req.whenComplete().isCompletedExceptionally()) {
            state = State.COMPLETING;
            req.whenComplete().handle((unused, cause) -> {
                abort(cause);
                return null;
            });
            return true;
        }

        if (res.isComplete()) {
            state = State.COMPLETING;
            res.whenComplete().handle((result, cause) -> {
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
                abort(abortCause);
                return null;
            });
            return true;
        }

        return false;
    }
}
