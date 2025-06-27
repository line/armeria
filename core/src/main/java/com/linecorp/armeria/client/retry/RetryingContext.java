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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;

class RetryingContext {
    private final ClientRequestContext ctx;
    private final RetryConfig<HttpResponse> retryConfig;
    private final HttpRequest req;
    private final HttpRequestDuplicator reqDuplicator;
    private final HttpResponse res;
    private final CompletableFuture<HttpResponse> resFuture;

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
    }

    RetryConfig<HttpResponse> config() {
        return retryConfig;
    }

    ClientRequestContext ctx() {
        return ctx;
    }

    HttpRequestDuplicator reqDuplicator() {
        return reqDuplicator;
    }

    HttpRequest req() {
        return req;
    }

    HttpResponse res() {
        return res;
    }

    CompletableFuture<HttpResponse> resFuture() {
        return resFuture;
    }

    void commit(RetryAttempt attempt) {
        assert attempt.state() == RetryAttempt.State.COMPLETED;
        final HttpResponse attemptRes = attempt.commit();
        ctx.logBuilder().endResponseWithLastChild();
        resFuture.complete(attemptRes);
        reqDuplicator.close();
    }

    void abort(Throwable cause) {
        abort(cause, false);
    }

    void abort(Throwable cause, boolean endRequestLog) {
        resFuture.completeExceptionally(cause);
        reqDuplicator.abort(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
    }
}
