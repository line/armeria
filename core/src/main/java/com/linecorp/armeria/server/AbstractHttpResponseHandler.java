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
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeResponseHeaders;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeTrailers;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;

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

    AbstractHttpResponseHandler(ChannelHandlerContext ctx,
                                ServerHttpObjectEncoder responseEncoder,
                                DefaultServiceRequestContext reqCtx, DecodedHttpRequest req) {
        this.ctx = ctx;
        this.responseEncoder = responseEncoder;
        this.reqCtx = reqCtx;
        this.req = req;
    }

    /**
     * Returns whether a response has been finished.
     */
    abstract boolean isDone();

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
            Throwable cause = CapturedServiceException.get(reqCtx);
            if (cause == null) {
                if (reqCtx.sessionProtocol().isMultiplex()) {
                    cause = ClosedSessionException.get();
                } else {
                    cause = ClosedStreamException.get();
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

        final ResponseHeaders headers = mergeResponseHeaders(res.headers(), reqCtx.additionalResponseHeaders());
        final HttpData content = res.content();
        final boolean contentEmpty = content.isEmpty();
        final HttpHeaders trailers = mergeTrailers(res.trailers(), reqCtx.additionalResponseTrailers());
        final boolean trailersEmpty = trailers.isEmpty();

        if (!res.informationals().isEmpty()) {
            for (ResponseHeaders informational : res.informationals()) {
                responseEncoder.writeHeaders(id, streamId, informational,
                                             false, trailersEmpty);
            }
        }
        logBuilder().responseHeaders(headers);
        ChannelFuture future = responseEncoder.writeHeaders(id, streamId, headers,
                                                            contentEmpty && trailersEmpty, trailersEmpty);
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
        final AggregatedHttpResponse response =
                serviceConfig.server().config().errorHandler()
                             .renderStatus(serviceConfig, status, null, cause0);
        assert response != null;
        return response;
    }

    final void endLogRequestAndResponse(Throwable cause) {
        logBuilder().endRequest(cause);
        logBuilder().endResponse(cause);
    }

    final void endLogRequestAndResponse() {
        logBuilder().endRequest();
        logBuilder().endResponse();
    }

    /**
     * Writes an access log if the {@link TransientServiceOption#WITH_ACCESS_LOGGING} option is enabled for
     * the {@link HttpService}.
     */
    final void maybeWriteAccessLog() {
        final ServiceConfig config = reqCtx.config();
        if (config.transientServiceOptions().contains(TransientServiceOption.WITH_ACCESS_LOGGING)) {
            reqCtx.log().whenComplete().thenAccept(config.accessLogWriter()::log);
        }
    }

    /**
     * Schedules a request timeout.
     */
    final void scheduleTimeout() {
        // Schedule the initial request timeout with the timeoutNanos in the CancellationScheduler
        reqCtx.requestCancellationScheduler().init(reqCtx.eventLoop(), newCancellationTask(),
                                                   0, /* server */ true);
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
