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

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractRetryingClient
        <I extends Request, O extends Response, R extends RetryingContext<I, O>>
        extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRetryingClient.class);

    /**
     * The header which indicates the retry count of a {@link Request}.
     * The server might use this value to reject excessive retries, etc.
     */
    public static final AsciiString ARMERIA_RETRY_COUNT = HttpHeaderNames.of("armeria-retry-count");

    private final RetryConfigMapping<O> retryMapping;

    @Nullable
    private final RetryConfig<O> retryConfig;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractRetryingClient(
            Client<I, O> delegate, RetryConfigMapping<O> retryMapping, @Nullable RetryConfig<O> retryConfig) {
        super(delegate);
        this.retryMapping = requireNonNull(retryMapping, "retryMapping");
        this.retryConfig = retryConfig;
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final RetryConfig<O> retryConfigForReq =
                retryConfig != null ? retryConfig
                                    : requireNonNull(retryMapping.get(ctx, req),
                                                     "retryMapping.get() returned null");

        final R rctx = getRetryingContext(ctx, retryConfigForReq, req);
        rctx.init().handle((initSuccessful, initCause) -> {
            if (!initSuccessful || initCause != null) {
                // todo(szymon): comment here.
                logger.debug("RetryingContext initialization failed, not retrying: {}", rctx, initCause);
                return null;
            }

            executeAttempt(null, rctx);
            return null;
        });

        return rctx.res();
    }

    abstract R getRetryingContext(ClientRequestContext ctx, RetryConfig<O> config, I req);

    private void executeAttempt(@Nullable Backoff lastBackoff, R rctx) {
        rctx.executeAttempt(lastBackoff, unwrap())
            .handle((decision, decisionCause) -> {
                if (decisionCause != null) {
                    rctx.abort(decisionCause);
                    return null;
                }

                final Backoff backoff = decision != null ? decision.backoff() : null;
                final long nextRetryTimeNanos;
                if (backoff != null) {
                    nextRetryTimeNanos = rctx.nextRetryTimeNanos(backoff);
                } else {
                    nextRetryTimeNanos = Long.MAX_VALUE;
                }

                if (nextRetryTimeNanos < Long.MAX_VALUE) {
                    rctx.abortAttempt();
                    rctx.scheduleNextRetry(
                            nextRetryTimeNanos,
                            () -> executeAttempt(backoff, rctx),
                            rctx::abort
                    );
                } else {
                    rctx.commit();
                }

                return null;
            });
    }
}
