/*
 * Copyright 2016 LINE Corporation
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
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeTrailers;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CancellationException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.EmptyHttpResponseException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Error;

abstract class AbstractHttpResponseSubscriber extends AbstractHttpResponseHandler
        implements Subscriber<HttpObject> {

    static final Logger logger = LoggerFactory.getLogger(AbstractHttpResponseSubscriber.class);

    enum State {
        NEEDS_HEADERS,
        NEEDS_DATA,
        NEEDS_DATA_OR_TRAILERS,
        NEEDS_TRAILERS,
        DONE,
    }

    @Nullable
    private Subscription subscription;
    private State state = State.NEEDS_HEADERS;

    private boolean isSubscriptionCompleted;

    private boolean loggedResponseHeadersFirstBytesTransferred;

    @Nullable
    private WriteHeadersFutureListener cachedWriteHeadersListener;

    @Nullable
    private WriteDataFutureListener cachedWriteDataListener;

    AbstractHttpResponseSubscriber(ChannelHandlerContext ctx, ServerHttpObjectEncoder responseEncoder,
                                   DefaultServiceRequestContext reqCtx, DecodedHttpRequest req,
                                   CompletableFuture<Void> completionFuture) {
        super(ctx, responseEncoder, reqCtx, req, completionFuture);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;
        if (state == State.DONE) {
            if (!isSubscriptionCompleted) {
                isSubscriptionCompleted = true;
                subscription.cancel();
            }
            return;
        }

        scheduleTimeout();

        // Start consuming.
        subscription.request(1);
    }

    @SuppressWarnings("checkstyle:FallThrough")
    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            req.abortResponse(new IllegalArgumentException(
                    "published an HttpObject that's neither HttpHeaders nor HttpData: " + o +
                    " (service: " + service() + ')'), true);
            PooledObjects.close(o);
            return;
        }

        if (failIfStreamOrSessionClosed()) {
            PooledObjects.close(o);
            setDone(true);
            return;
        }

        switch (state) {
            case NEEDS_HEADERS: {
                logBuilder().startResponse();
                if (!(o instanceof ResponseHeaders)) {
                    req.abortResponse(new IllegalStateException(
                            "published an HttpData without a preceding ResponseHeaders: " + o +
                            " (service: " + service() + ')'), true);
                    return;
                }

                if (responseEncoder.isResponseHeadersSent(req.id(), req.streamId())) {
                    // The response is sent by the HttpRequestDecoder so we just cancel the stream message.
                    tryComplete(new CancelledSubscriptionException(
                            "An HTTP response was sent already. ctx: " + reqCtx));
                    setDone(true);
                    return;
                }
                onResponseHeaders((ResponseHeaders) o);
                break;
            }
            case NEEDS_TRAILERS: {
                if (o instanceof ResponseHeaders) {
                    req.abortResponse(new IllegalStateException(
                            "published a ResponseHeaders: " + o +
                            " (expected: an HTTP trailers). service: " + service()), true);
                    return;
                }
                if (o instanceof HttpData) {
                    // We silently ignore the data and call subscription.request(1).
                    ((HttpData) o).close();
                    assert subscription != null;
                    subscription.request(1);
                    return;
                }
                // We handle the trailers in NEEDS_DATA_OR_TRAILERS.
            }
            case NEEDS_DATA_OR_TRAILERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailers = (HttpHeaders) o;
                    if (trailers.contains(HttpHeaderNames.STATUS)) {
                        req.abortResponse(new IllegalArgumentException(
                                "published an HTTP trailers with status: " + o +
                                " (service: " + service() + ')'), true);
                        return;
                    }
                    setDone(false);

                    final HttpHeaders merged = mergeTrailers(trailers, reqCtx.additionalResponseTrailers());
                    logBuilder().responseTrailers(merged);
                    responseEncoder.writeTrailers(req.id(), req.streamId(), merged)
                                   .addListener(writeHeadersFutureListener(true));
                } else {
                    final boolean endOfStream = o.isEndOfStream();
                    final HttpData data = (HttpData) o;
                    data.touch(reqCtx);
                    final boolean wroteEmptyData = data.isEmpty();
                    logBuilder().increaseResponseLength(data);
                    if (endOfStream) {
                        setDone(false);
                    }

                    final HttpHeaders additionalTrailers = reqCtx.additionalResponseTrailers();
                    if (endOfStream && !additionalTrailers.isEmpty()) { // Last DATA frame
                        responseEncoder.writeData(req.id(), req.streamId(), data, false)
                                       .addListener(writeDataFutureListener(false, wroteEmptyData));
                        logBuilder().responseTrailers(additionalTrailers);
                        responseEncoder.writeTrailers(req.id(), req.streamId(), additionalTrailers)
                                       .addListener(writeHeadersFutureListener(true));
                    } else {
                        responseEncoder.writeData(req.id(), req.streamId(), data, endOfStream)
                                       .addListener(writeDataFutureListener(endOfStream, wroteEmptyData));
                    }
                }
                break;
            }
            case NEEDS_DATA: {
                if (!(o instanceof HttpData)) {
                    req.abortResponse(new IllegalStateException(
                            o + " is published. (expected: an HttpData) (service: " + service() + ')'), true);
                    return;
                }

                final boolean endOfStream = o.isEndOfStream();
                final HttpData data = (HttpData) o;
                data.touch(reqCtx);
                final boolean wroteEmptyData = data.isEmpty();
                logBuilder().increaseResponseLength(data);
                if (endOfStream) {
                    setDone(false);
                }
                responseEncoder.writeData(req.id(), req.streamId(), data, endOfStream)
                               .addListener(writeDataFutureListener(endOfStream, wroteEmptyData));
                break;
            }
            case DONE:
                isSubscriptionCompleted = true;
                subscription.cancel();
                PooledObjects.close(o);
                return;
        }

        ctx.flush();
    }

    abstract void onResponseHeaders(ResponseHeaders headers);

    void setState(State state) {
        this.state = state;
    }

    @Override
    boolean isDone() {
        return state == State.DONE;
    }

    State setDone(boolean cancel) {
        if (cancel && subscription != null && !isSubscriptionCompleted) {
            isSubscriptionCompleted = true;
            subscription.cancel();
        }
        clearTimeout();
        final State oldState = state;
        state = State.DONE;
        return oldState;
    }

    @Override
    public void onError(Throwable cause) {
        isSubscriptionCompleted = true;
        final Throwable peeled = Exceptions.peel(cause);
        if (!isWritable()) {
            // A session or stream is currently being closing or is closed already.
            fail(peeled);
            return;
        }

        if (peeled instanceof HttpResponseException) {
            // Timeout may occur when the aggregation of the error response takes long.
            // If timeout occurs, the response is sent by newCancellationTask().
            toAggregatedHttpResponse((HttpResponseException) peeled).handleAsync((res, throwable) -> {
                if (throwable != null) {
                    failAndRespond(throwable,
                                   internalServerErrorResponse,
                                   Http2Error.CANCEL, false);
                } else {
                    failAndRespond(peeled, res, Http2Error.CANCEL, false);
                }
                return null;
            }, ctx.executor());
        } else if (peeled instanceof HttpStatusException) {
            final Throwable cause0 = firstNonNull(peeled.getCause(), peeled);
            final AggregatedHttpResponse res = toAggregatedHttpResponse((HttpStatusException) peeled);
            failAndRespond(cause0, res, Http2Error.CANCEL, false);
        } else if (Exceptions.isStreamCancelling(peeled)) {
            failAndReset(peeled);
        } else {
            if (!(peeled instanceof CancellationException)) {
                logger.warn("{} Unexpected exception from a service or a response publisher: {}",
                            ctx.channel(), service(), peeled);
            } else {
                // Ignore CancellationException and its subtypes, which can be triggered when the request
                // was cancelled or timed out even before the subscription attempt is made.
            }

            failAndRespond(peeled, internalServerErrorResponse, Http2Error.INTERNAL_ERROR, false);
        }
    }

    @Override
    public void onComplete() {
        isSubscriptionCompleted = true;

        final State oldState = setDone(false);
        if (oldState == State.NEEDS_HEADERS) {
            responseEncoder.writeReset(req.id(), req.streamId(), Http2Error.INTERNAL_ERROR, false)
                           .addListener(future -> {
                               try (SafeCloseable ignored = RequestContextUtil.pop()) {
                                   fail(EmptyHttpResponseException.get());
                               }
                           });
            ctx.flush();
            return;
        }

        if (oldState != State.DONE) {
            final HttpHeaders additionalTrailers = reqCtx.additionalResponseTrailers();
            if (!additionalTrailers.isEmpty()) {
                logBuilder().responseTrailers(additionalTrailers);
                responseEncoder.writeTrailers(req.id(), req.streamId(), additionalTrailers)
                               .addListener(writeHeadersFutureListener(true));
                ctx.flush();
            } else {
                if (isWritable()) {
                    responseEncoder.writeData(req.id(), req.streamId(), HttpData.empty(), true)
                                   .addListener(writeDataFutureListener(true, true));
                    ctx.flush();
                } else {
                    if (!reqCtx.sessionProtocol().isMultiplex()) {
                        // An HTTP/1 connection is closed by a remote peer after all data is sent,
                        // so we can assume the HTTP/1 request is complete successfully.
                        succeed();
                    } else {
                        fail(ClosedStreamException.get());
                    }
                }
            }
        }
    }

    private void succeed() {
        if (tryComplete(null)) {
            Throwable cause = null;
            final RequestLog requestLog = reqCtx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
            if (requestLog != null) {
                cause = requestLog.responseCause();
            }
            endLogRequestAndResponse(cause);
            maybeWriteAccessLog();
        }
    }

    @Override
    void fail(Throwable cause) {
        if (tryComplete(cause)) {
            setDone(true);
            endLogRequestAndResponse(cause);
            maybeWriteAccessLog();
        }
    }

    private void failAndRespond(Throwable cause, AggregatedHttpResponse res, Http2Error error, boolean cancel) {
        final State oldState = setDone(cancel);
        final int id = req.id();
        final int streamId = req.streamId();

        final ChannelFuture future;
        final boolean isReset;
        if (oldState == State.NEEDS_HEADERS) { // ResponseHeaders is not sent yet, so we can send the response.
            future = writeAggregatedHttpResponse(res);
            isReset = false;
        } else {
            // Wrote something already; we have to reset/cancel the stream.
            future = responseEncoder.writeReset(id, streamId, error, false);
            isReset = true;
        }

        addCallbackAndFlush(cause, oldState, future, isReset);
    }

    private void failAndReset(Throwable cause) {
        final State oldState = setDone(false);
        final ChannelFuture future =
                responseEncoder.writeReset(req.id(), req.streamId(), Http2Error.CANCEL, false);

        addCallbackAndFlush(cause, oldState, future, true);
    }

    private void addCallbackAndFlush(Throwable cause, State oldState, ChannelFuture future, boolean isReset) {
        if (oldState != State.DONE) {
            future.addListener(f -> {
                try (SafeCloseable ignored = RequestContextUtil.pop()) {
                    if (f.isSuccess() && !isReset) {
                        maybeLogFirstResponseBytesTransferred();
                        if (req.shouldResetOnlyIfRemoteIsOpen()) {
                            responseEncoder.writeReset(req.id(), req.streamId(), Http2Error.CANCEL, true);
                        }
                    }
                    // Write an access log always with a cause. Respect the first specified cause.
                    fail(cause);
                }
            });
        }
        ctx.flush();
    }

    WriteHeadersFutureListener writeHeadersFutureListener(boolean endOfStream) {
        if (!endOfStream) {
            // Reuse in case sending multiple informational headers.
            if (cachedWriteHeadersListener == null) {
                cachedWriteHeadersListener = new WriteHeadersFutureListener(false);
            }
            return cachedWriteHeadersListener;
        }
        return new WriteHeadersFutureListener(true);
    }

    WriteDataFutureListener writeDataFutureListener(boolean endOfStream, boolean wroteEmptyData) {
        if (!endOfStream && !wroteEmptyData) {
            // Reuse in case sending streaming data.
            if (cachedWriteDataListener == null) {
                cachedWriteDataListener = new WriteDataFutureListener(false, false);
            }
            return cachedWriteDataListener;
        }
        return new WriteDataFutureListener(endOfStream, wroteEmptyData);
    }

    private class WriteHeadersFutureListener implements ChannelFutureListener {
        private final boolean endOfStream;

        WriteHeadersFutureListener(boolean endOfStream) {
            this.endOfStream = endOfStream;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                handleWriteComplete(future, endOfStream, future.isSuccess());
            }
        }
    }

    private class WriteDataFutureListener implements ChannelFutureListener {
        private final boolean endOfStream;
        private final boolean wroteEmptyData;

        WriteDataFutureListener(boolean endOfStream, boolean wroteEmptyData) {
            this.endOfStream = endOfStream;
            this.wroteEmptyData = wroteEmptyData;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                final boolean isSuccess;
                if (future.isSuccess()) {
                    isSuccess = true;
                } else {
                    // If 1) the last chunk we attempted to send was empty,
                    //    2) the connection has been closed,
                    //    3) and the protocol is HTTP/1,
                    // it is very likely that a client closed the connection after receiving the
                    // complete content, which is not really a problem.
                    final Throwable cause = future.cause();
                    isSuccess = endOfStream && wroteEmptyData &&
                                responseEncoder instanceof Http1ObjectEncoder &&
                                (cause instanceof ClosedChannelException ||
                                 // A ClosedSessionException may be raised by HttpObjectEncoder
                                 // if a channel was closed.
                                 cause instanceof ClosedSessionException);
                }
                handleWriteComplete(future, endOfStream, isSuccess);
            }
        }
    }

    void handleWriteComplete(ChannelFuture future, boolean endOfStream, boolean isSuccess) throws Exception {
        // Write an access log if:
        // - every message has been sent successfully.
        // - any write operation is failed with a cause.
        if (isSuccess) {
            maybeLogFirstResponseBytesTransferred();

            if (endOfStream) {
                succeed();
            }

            if (!isSubscriptionCompleted) {
                assert subscription != null;
                // Even though an 'endOfStream' is received, we still need to send a request signal to the
                // upstream for completing or canceling this 'HttpResponseSubscriber'
                subscription.request(1);
            }
            return;
        }

        fail(future.cause());
        // We do not send RST but close the channel because there's high chances that the channel
        // is not reusable if an exception was raised while writing to the channel.
        HttpServerHandler.CLOSE_ON_FAILURE.operationComplete(future);
    }

    private void maybeLogFirstResponseBytesTransferred() {
        if (!loggedResponseHeadersFirstBytesTransferred) {
            loggedResponseHeadersFirstBytesTransferred = true;
            logBuilder().responseFirstBytesTransferred();
        }
    }
}
