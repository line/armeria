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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;

final class RpcRetryAttempt implements RetryAttempt<RpcResponse> {
    // todo(szymon): doc
    enum State {
        EXECUTING,
        DECIDING,
        DECIDED,
        COMMITTED,
        ABORTED
    }

    State state;

    private final RpcRetryingContext rctx;
    private final ClientRequestContext ctx;
    private final RpcResponse res;
    @Nullable
    private Throwable resCause;
    private final CompletableFuture<@Nullable RetryDecision> whenDecidedFuture;

    RpcRetryAttempt(RpcRetryingContext rctx, ClientRequestContext ctx,
                    RpcResponse res) {
        this.rctx = rctx;
        this.ctx = ctx;
        this.res = res;
        resCause = null;
        whenDecidedFuture = new CompletableFuture<>();

        state = State.EXECUTING;

        res.handle((unused, cause) -> {
            resCause = cause;
            decide();
            return null;
        });
    }

    private void decide() {
        assert state == State.EXECUTING;
        state = State.DECIDING;

        final RetryRuleWithContent<RpcResponse> retryRule =
                rctx.config().needsContentInRule() ? rctx.config().retryRuleWithContent()
                                                   : rctx.config().fromRetryRule();

        assert retryRule != null;
        try {
            retryRule.shouldRetry(ctx, res, resCause)
                     .handle((decision, cause) -> {
                         state = State.DECIDED;

                         if (cause == null) {
                             whenDecidedFuture.complete(decision);
                         } else {
                             whenDecidedFuture.completeExceptionally(cause);
                         }
                         return null;
                     });
        } catch (Throwable t) {
            state = State.DECIDED;
            whenDecidedFuture.completeExceptionally(t);
        }
    }

    RpcResponse commit() {
        if (state == State.COMMITTED) {
            return res;
        }

        assert state == State.DECIDED;
        state = State.COMMITTED;
        return res;
    }

    void abort() {
        if (state == State.ABORTED) {
            return;
        }

        assert state == State.DECIDED;
        state = State.ABORTED;
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

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("state", state)
                .add("rctx", rctx)
                .add("ctx", ctx)
                .add("res", res)
                .add("resCause", resCause)
                .toString();
    }
}
