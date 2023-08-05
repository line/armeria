/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CancellationException;
import com.linecorp.armeria.common.EmptyHttpResponseException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;

final class AggregatedHttpResponseHandler extends AbstractHttpResponseHandler
        implements BiFunction<AggregatedHttpResponse, Throwable, Void> {

    private static final Logger logger = LoggerFactory.getLogger(AggregatedHttpResponseHandler.class);

    AggregatedHttpResponseHandler(ChannelHandlerContext ctx, ServerHttpObjectEncoder responseEncoder,
                                  DefaultServiceRequestContext reqCtx, DecodedHttpRequest req,
                                  CompletableFuture<Void> completionFuture) {
        super(ctx, responseEncoder, reqCtx, req, completionFuture);
        scheduleTimeout();
    }

    @Override
    public Void apply(@Nullable AggregatedHttpResponse response, @Nullable Throwable cause) {
        final EventLoop eventLoop = reqCtx.eventLoop();
        if (eventLoop.inEventLoop()) {
            apply0(response, cause);
        } else {
            eventLoop.execute(() -> apply0(response, cause));
        }
        return null;
    }

    private void apply0(@Nullable AggregatedHttpResponse response, @Nullable Throwable cause) {
        clearTimeout();
        if (cause != null) {
            cause = Exceptions.peel(cause);
            recoverAndWrite(cause);
            return;
        }

        assert response != null;
        if (failIfStreamOrSessionClosed()) {
            response.content().close();
            return;
        }

        logBuilder().startResponse();
        write(response, null);
    }

    private void write(AggregatedHttpResponse response, @Nullable Throwable cause) {
        final ChannelFuture future = writeAggregatedHttpResponse(response);
        future.addListener(new WriteFutureListener(response.content().isEmpty(), cause));
        ctx.flush();
    }

    private void recoverAndWrite(Throwable cause) {
        if (cause instanceof HttpResponseException) {
            toAggregatedHttpResponse((HttpResponseException) cause).handleAsync((res, cause0) -> {
                if (cause0 != null) {
                    cause0 = Exceptions.peel(cause0);
                    write(internalServerErrorResponse, cause0);
                } else {
                    write(res, cause);
                }
                return null;
            }, ctx.executor());
        } else if (cause instanceof HttpStatusException) {
            final Throwable cause0 = firstNonNull(cause.getCause(), cause);
            write(toAggregatedHttpResponse((HttpStatusException) cause), cause0);
        } else if (Exceptions.isStreamCancelling(cause) || cause instanceof EmptyHttpResponseException) {
            resetAndFail(cause);
        } else {
            if (!(cause instanceof CancellationException)) {
                logger.warn("{} Unexpected exception from a service or a response publisher: {}",
                            ctx.channel(), service(), cause);
            } else {
                // Ignore CancellationException and its subtypes, which can be triggered when the request
                // was cancelled or timed out even before the subscription attempt is made.
            }
            write(internalServerErrorResponse, cause);
        }
    }

    @Override
    void fail(Throwable cause) {
        if (tryComplete(cause)) {
            endLogRequestAndResponse(cause);
            maybeWriteAccessLog();
        }
    }

    private void resetAndFail(Throwable cause) {
        responseEncoder.writeReset(req.id(), req.streamId(), Http2Error.CANCEL).addListener(f -> {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                fail(cause);
            }
        });
        ctx.flush();
    }

    private final class WriteFutureListener implements ChannelFutureListener {
        private final boolean wroteEmptyData;
        @Nullable
        private final Throwable cause;

        WriteFutureListener(boolean wroteEmptyData, @Nullable Throwable cause) {
            this.wroteEmptyData = wroteEmptyData;
            this.cause = cause;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                final boolean isSuccess;
                if (future.isSuccess()) {
                    isSuccess = true;
                } else {
                    // If 1) the data we attempted to send was empty,
                    //    2) the connection has been closed,
                    //    3) and the protocol is HTTP/1,
                    // it is very likely that a client closed the connection after receiving the
                    // complete content, which is not really a problem.
                    isSuccess = wroteEmptyData &&
                                future.cause() instanceof ClosedChannelException &&
                                responseEncoder instanceof Http1ObjectEncoder;
                }
                handleWriteComplete(future, isSuccess, cause);
            }
        }
    }

    void handleWriteComplete(ChannelFuture future, boolean isSuccess, @Nullable Throwable cause)
            throws Exception {
        // Write an access log if:
        // - every message has been sent successfully.
        // - any write operation is failed with a cause.
        if (isSuccess) {
            logBuilder().responseFirstBytesTransferred();
            if (tryComplete(cause)) {
                if (cause == null) {
                    final RequestLog requestLog = reqCtx.log()
                            .getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                    if (requestLog != null) {
                        cause = requestLog.responseCause();
                    }
                }
                endLogRequestAndResponse(cause);
                maybeWriteAccessLog();
            }
            return;
        }

        fail(future.cause());
        // We do not send RST but close the channel because there's high chances that the channel
        // is not reusable if an exception was raised while writing to the channel.
        HttpServerHandler.CLOSE_ON_FAILURE.operationComplete(future);
    }
}
