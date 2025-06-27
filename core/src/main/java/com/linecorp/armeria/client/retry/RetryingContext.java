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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.stream.AbortedStreamException;

class RetryingContext {
    private enum State {
        RETRYING,
        COMPLETING,
        COMPLETED
    }

    private final ClientRequestContext ctx;
    private final RetryConfig<HttpResponse> retryConfig;
    private final HttpRequest req;
    private final HttpRequestDuplicator reqDuplicator;
    private final HttpResponse res;
    private final CompletableFuture<HttpResponse> resFuture;

    private State state;

    RetryingContext(ClientRequestContext ctx,
                    RetryConfig<HttpResponse> retryConfig,
                    HttpRequest req,
                    HttpRequestDuplicator reqDuplicator,
                    HttpResponse res,
                    CompletableFuture<HttpResponse> resFuture) {

        this.ctx = ctx;
        this.retryConfig = retryConfig;
        this.req = req;
        this.reqDuplicator = reqDuplicator;
        this.res = res;
        this.resFuture = resFuture;
        state = State.RETRYING;
    }

    RetryConfig<HttpResponse> config() {
        return retryConfig;
    }

    ClientRequestContext ctx() {
        return ctx;
    }

    boolean isCompleted() {
        if (state == State.COMPLETING || state == State.COMPLETED) {
            return true;
        }

        assert state == State.RETRYING;

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

    public ClientRequestContext newAttemptContext(int attemptNumber) {
        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!AbstractRetryingClient.setResponseTimeout(ctx)) {
            throw ResponseTimeoutException.get();
        }

        final HttpRequest attemptReq;
        if (isInitialAttempt) {
            attemptReq = reqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder attemptHeadersBuilder = req.headers().toBuilder();
            attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, attemptNumber - 1);
            attemptReq = reqDuplicator.duplicate(attemptHeadersBuilder.build());
        }

        return AbstractRetryingClient.newAttemptContext(
                    ctx, attemptReq, ctx.rpcRequest(), isInitialAttempt);
    }

    void commit(RetryAttempt attempt) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }
        assert attempt.state() == RetryAttempt.State.COMPLETED;
        assert state == State.RETRYING;
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

        assert state == State.RETRYING || state == State.COMPLETING;
        state = State.COMPLETED;

        reqDuplicator.abort(cause);

        // todo(szymon): verify that this safe to do so we can avoid isInitialAttempt check
        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }

        ctx.logBuilder().endResponse(cause);

        resFuture.completeExceptionally(cause);
    }
}
