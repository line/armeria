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
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.CLOSE_STRING;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeResponseHeaders;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeTrailers;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

abstract class AbstractHttpResponseHandler {

    static final AggregatedHttpResponse internalServerErrorResponse =
            AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);

    final ChannelHandlerContext ctx;
    final ServerHttpObjectEncoder responseEncoder;
    final DefaultServiceRequestContext reqCtx;
    final DecodedHttpRequest req;

    private final CompletableFuture<Void> completionFuture;
    private boolean isComplete;

    AbstractHttpResponseHandler(ChannelHandlerContext ctx,
                                ServerHttpObjectEncoder responseEncoder,
                                DefaultServiceRequestContext reqCtx, DecodedHttpRequest req,
                                CompletableFuture<Void> completionFuture) {
        this.ctx = ctx;
        this.responseEncoder = responseEncoder;
        this.reqCtx = reqCtx;
        this.req = req;
        this.completionFuture = completionFuture;
    }

    /**
     * Returns whether a response has been finished.
     */
    boolean isDone() {
        return isComplete;
    }

    void disconnectWhenFinished() {
        responseEncoder.keepAliveHandler().disconnectWhenFinished();
    }

    final boolean tryComplete(@Nullable Throwable cause) {
        if (isComplete) {
            return false;
        }
        isComplete = true;
        if (cause == null) {
            completionFuture.complete(null);
        } else {
            completionFuture.completeExceptionally(cause);
        }

        return true;
    }

    /**
     * Fails a request and a response with the specified {@link Throwable}.
     * This method won't send any response to the remote peer but log the failed request.
     */
    abstract void fail(Throwable cause);

    final boolean failIfStreamOrSessionClosed() {
        // Make sure that a stream exists before writing data.
        // The following situation may cause the data to be written to a closed stream.
        // 1. A connection that has pending outbound buffers receives GOAWAY frame.
        // 2. AbstractHttp2ConnectionHandler.close() clears and flushes all active streams.
        // 3. After successfully flushing, the listener requests next data and
        //    the subscriber attempts to write the next data to the stream closed at 2).
        if (!isWritable()) {
            Throwable cause = null;
            final RequestLog requestLog = reqCtx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
            if (requestLog != null) {
                cause = requestLog.responseCause();
            }
            if (cause == null) {
                if (reqCtx.sessionProtocol().isMultiplex()) {
                    cause = ClosedStreamException.get();
                } else {
                    cause = ClosedSessionException.get();
                }
            }
            fail(cause);
            return true;
        }
        return false;
    }

    final boolean isWritable() {
        return responseEncoder.isWritable(req.id(), req.streamId());
    }

    /**
     * Writes the {@link AggregatedHttpResponse} to the {@link Channel}.
     * Note that the caller has to flush the written data when needed.
     */
    final ChannelFuture writeAggregatedHttpResponse(AggregatedHttpResponse res) {
        final int id = req.id();
        final int streamId = req.streamId();

        final ServerConfig config = reqCtx.config().server().config();
        ResponseHeaders headers = mergeResponseHeaders(res.headers(), reqCtx.additionalResponseHeaders(),
                                                       reqCtx.config().defaultHeaders(),
                                                       config.isServerHeaderEnabled(),
                                                       config.isDateHeaderEnabled());
        final String connectionOption = headers.get(HttpHeaderNames.CONNECTION);
        if (CLOSE_STRING.equalsIgnoreCase(connectionOption)) {
            disconnectWhenFinished();
        }

        final HttpData content = res.content();
        content.touch(reqCtx);
        // An aggregated response always has empty content if its status.isContentAlwaysEmpty() is true.
        assert !res.status().isContentAlwaysEmpty() || content.isEmpty();
        final boolean contentEmpty;
        if (content.isEmpty()) {
            contentEmpty = true;
        } else if (req.method() == HttpMethod.HEAD) {
            contentEmpty = true;
            // Need to release the body because we're not passing it over to the encoder.
            content.close();
        } else {
            contentEmpty = false;
        }

        final HttpHeaders trailers = mergeTrailers(res.trailers(), reqCtx.additionalResponseTrailers());
        final boolean trailersEmpty = trailers.isEmpty();

        if (reqCtx.sessionProtocol().isMultiplex() && !contentEmpty && headers.contentLength() == -1) {
            // If a trailers is set, a content-length is automatically removed by
            // `ArmeriaHttpUtil.setOrRemoveContentLength()` when creating `AggregatedHttpResponse`.
            // However, in HTTP/2, a content-length could be set with a trailers.
            headers = headers.toBuilder()
                             .contentLength(content.length())
                             .build();
        }

        final HttpMethod method = reqCtx.method();
        if (!res.informationals().isEmpty()) {
            for (ResponseHeaders informational : res.informationals()) {
                responseEncoder.writeHeaders(id, streamId, informational,
                                             false, trailersEmpty, method);
            }
        }
        logBuilder().responseHeaders(headers);
        ChannelFuture future =
                responseEncoder.writeHeaders(id, streamId, headers, contentEmpty && trailersEmpty,
                                             trailersEmpty, method);
        if (!contentEmpty) {
            logBuilder().increaseResponseLength(content);
            future = responseEncoder.writeData(id, streamId, content, trailersEmpty);
        }
        if (!trailersEmpty) {
            logBuilder().responseTrailers(trailers);
            future = responseEncoder.writeTrailers(id, streamId, trailers);
        }
        return future;
    }

    final CompletableFuture<AggregatedHttpResponse> toAggregatedHttpResponse(HttpResponseException cause) {
        return cause.httpResponse().aggregate(ctx.executor());
    }

    final AggregatedHttpResponse toAggregatedHttpResponse(HttpStatusException cause) {
        final HttpStatus status = cause.httpStatus();
        final Throwable cause0 = firstNonNull(cause.getCause(), cause);
        final ServiceConfig serviceConfig = reqCtx.config();
        final AggregatedHttpResponse response = serviceConfig.errorHandler()
                                                             .renderStatus(reqCtx, req.headers(), status,
                                                                           null, cause0);
        assert response != null;
        return response;
    }

    final void endLogRequestAndResponse(@Nullable Throwable cause) {
        if (cause != null) {
            logBuilder().endRequest(cause);
            logBuilder().endResponse(cause);
        } else {
            logBuilder().endRequest();
            logBuilder().endResponse();
        }
    }

    /**
     * Writes an access log if the {@link TransientServiceOption#WITH_ACCESS_LOGGING} option is enabled for
     * the {@link #service()}.
     */
    final void maybeWriteAccessLog() {
        final ServiceConfig config = reqCtx.config();
        if (config.transientServiceOptions().contains(TransientServiceOption.WITH_ACCESS_LOGGING)) {
            reqCtx.log().whenComplete().thenAccept(log -> {
                try (SafeCloseable ignored = reqCtx.push()) {
                    config.accessLogWriter().log(log);
                }
            });
        }
    }

    /**
     * Schedules a request timeout.
     */
    final void scheduleTimeout() {
        // Schedule the initial request timeout with the timeoutNanos in the CancellationScheduler
        reqCtx.requestCancellationScheduler().start(newCancellationTask());
    }

    /**
     * Clears the scheduled request timeout.
     */
    final void clearTimeout() {
        reqCtx.requestCancellationScheduler().clearTimeout(false);
    }

    final CancellationTask newCancellationTask() {
        return new CancellationTask() {
            @Override
            public boolean canSchedule() {
                return !isDone();
            }

            @Override
            public void run(Throwable cause) {
                // This method will be invoked only when `canSchedule()` returns true.
                assert !isDone();

                if (cause instanceof ClosedStreamException) {
                    // A stream or connection was already closed by a client
                    fail(cause);
                } else {
                    if (reqCtx.sessionProtocol().isMultiplex()) {
                        req.setShouldResetOnlyIfRemoteIsOpen(true);
                    } else if (req.isOpen()) {
                        disconnectWhenFinished();
                    }

                    req.abortResponse(cause, false);
                }
            }
        };
    }

    final HttpService service() {
        return reqCtx.config().service();
    }

    final RequestLogBuilder logBuilder() {
        return reqCtx.logBuilder();
    }
}
