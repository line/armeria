/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.HttpSessionHandler.MAX_NUM_REQUESTS_SENT;
import static com.linecorp.armeria.internal.client.ClosedStreamExceptionUtil.newClosedSessionException;
import static com.linecorp.armeria.internal.client.ClosedStreamExceptionUtil.newClosedStreamException;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.CLOSE_STRING;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeRequestHeaders;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.Http2Error;

abstract class AbstractHttpRequestHandler implements ChannelFutureListener {

    private static final Logger logger = LoggerFactory.getLogger(AbstractHttpRequestHandler.class);

    enum State {
        NEEDS_TO_WRITE_FIRST_HEADER,
        NEEDS_DATA,
        NEEDS_DATA_OR_TRAILERS,
        NEEDS_100_CONTINUE,
        DONE
    }

    private final Channel ch;
    private final ClientHttpObjectEncoder encoder;
    private final HttpResponseDecoder responseDecoder;
    private final DecodedHttpResponse originalRes;
    private final ClientRequestContext ctx;
    private final RequestLogBuilder logBuilder;
    private final long timeoutMillis;
    private final boolean headersOnly;
    private final boolean allowTrailers;
    private final boolean keepAlive;

    // session, id and responseWrapper are assigned in tryInitialize()
    @Nullable
    private HttpSession session;
    private int id = -1;
    @Nullable
    private HttpResponseWrapper responseWrapper;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.NEEDS_TO_WRITE_FIRST_HEADER;
    private boolean loggedRequestFirstBytesTransferred;
    private boolean failed;

    AbstractHttpRequestHandler(Channel ch, ClientHttpObjectEncoder encoder, HttpResponseDecoder responseDecoder,
                               DecodedHttpResponse originalRes,
                               ClientRequestContext ctx, long timeoutMillis, boolean headersOnly,
                               boolean allowTrailers, boolean keepAlive) {
        this.ch = ch;
        this.encoder = encoder;
        this.responseDecoder = responseDecoder;
        this.originalRes = originalRes;
        this.ctx = ctx;
        logBuilder = ctx.logBuilder();
        this.timeoutMillis = timeoutMillis;
        this.headersOnly = headersOnly;
        this.allowTrailers = allowTrailers;
        this.keepAlive = keepAlive;
    }

    abstract void onWriteSuccess();

    abstract void cancel();

    final Channel channel() {
        return ch;
    }

    final int id() {
        return id;
    }

    final State state() {
        return state;
    }

    /**
     * Invoked on each write of an {@link HttpObject}.
     */
    @Override
    public final void operationComplete(ChannelFuture future) throws Exception {
        // If a message has been sent out, cancel the timeout for starting a request.
        cancelTimeout();

        try (SafeCloseable ignored = RequestContextUtil.pop()) {
            if (future.isSuccess()) {
                // The first write is always the first headers, so log that we finished our first transfer
                // over the wire.
                if (!loggedRequestFirstBytesTransferred) {
                    logBuilder.requestFirstBytesTransferred();
                    loggedRequestFirstBytesTransferred = true;
                }

                if (state == State.DONE) {
                    logBuilder.endRequest();
                    // Successfully sent the request; schedule the response timeout.
                    assert responseWrapper != null;
                    responseWrapper.initTimeout();
                }

                if (state == State.NEEDS_100_CONTINUE) {
                    assert responseWrapper != null;
                    responseWrapper.initTimeout();
                }

                onWriteSuccess();
                return;
            }

            if (!loggedRequestFirstBytesTransferred) {
                fail(UnprocessedRequestException.of(future.cause()));
            } else {
                failAndReset(future.cause());
            }
        }
    }

    final boolean tryInitialize() {
        final HttpSession session = HttpSession.get(ch);
        id = session.incrementAndGetNumRequestsSent();
        if (id >= MAX_NUM_REQUESTS_SENT || !session.canSendRequest()) {
            final ClosedSessionException exception;
            if (id >= MAX_NUM_REQUESTS_SENT) {
                exception = new ClosedSessionException(
                        "Can't send requests more than " + MAX_NUM_REQUESTS_SENT +
                        " in one connection. ID: " + id);
            } else {
                exception = new ClosedSessionException(
                        "Can't send requests. ID: " + id + ", session active: " +
                        session.isAcquirable(responseDecoder.keepAliveHandler()));
            }
            session.markUnacquirable();
            // No need to send RST because we didn't send any packet and this will be disconnected anyway.
            fail(UnprocessedRequestException.of(exception));
            return false;
        }

        this.session = session;
        originalRes.setId(id);
        responseWrapper = responseDecoder.addResponse(this, id, originalRes, ctx, ch.eventLoop());

        if (timeoutMillis > 0) {
            // The timer would be executed if the first message has not been sent out within the timeout.
            timeoutFuture = ch.eventLoop().schedule(
                    () -> failAndReset(WriteTimeoutException.get()),
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }
        final CancellationScheduler scheduler = cancellationScheduler();
        if (scheduler != null) {
            scheduler.updateTask(newCancellationTask());
            if (ctx.responseTimeoutMode() == ResponseTimeoutMode.CONNECTION_ACQUIRED) {
                scheduler.start();
            }
        }
        if (ctx.isCancelled()) {
            // The previous cancellation task wraps the cause with an UnprocessedRequestException
            // so we return early
            return false;
        }
        return true;
    }

    private CancellationTask newCancellationTask() {
        return cause -> {
            if (ch.eventLoop().inEventLoop()) {
                try (SafeCloseable ignored = RequestContextUtil.pop()) {
                    failAndReset(cause);
                }
            } else {
                ch.eventLoop().execute(() -> failAndReset(cause));
            }
        };
    }

    RequestHeaders mergedRequestHeaders(RequestHeaders headers) {
        final HttpHeaders internalHeaders;
        final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
        if (ctxExtension == null) {
            internalHeaders = HttpHeaders.of();
        } else {
            internalHeaders = ctxExtension.internalRequestHeaders();
        }
        return mergeRequestHeaders(
                headers, ctx.defaultRequestHeaders(), ctx.additionalRequestHeaders(), internalHeaders);
    }

    /**
     * Writes the {@link RequestHeaders} to the {@link Channel}.
     * The {@link RequestHeaders} is merged with {@link ClientRequestContext#additionalRequestHeaders()}
     * before being written.
     * Note that the written data is not flushed by this method. The caller should explicitly call
     * {@link Channel#flush()} when each write unit is done.
     */
    final void writeHeaders(RequestHeaders headers, boolean needs100Continue) {
        assert session != null;
        final SessionProtocol protocol = session.protocol();
        assert protocol != null;
        if (needs100Continue) {
            state = State.NEEDS_100_CONTINUE;
        } else if (headersOnly) {
            state = State.DONE;
        } else if (allowTrailers) {
            state = State.NEEDS_DATA_OR_TRAILERS;
        } else {
            state = State.NEEDS_DATA;
        }

        logBuilder.requestHeaders(headers);

        final String connectionOption = headers.get(HttpHeaderNames.CONNECTION);
        if (CLOSE_STRING.equalsIgnoreCase(connectionOption) || !keepAlive) {
            // Make the session unhealthy so that subsequent requests do not use it.
            // In HTTP/2 request, the "Connection: close" is just interpreted as a signal to close the
            // connection by sending a GOAWAY frame that will be sent after receiving the corresponding
            // response from the remote peer. The "Connection: close" header is stripped when it is converted to
            // a Netty HTTP/2 header.
            session.markUnacquirable();
        }

        final ChannelPromise promise = ch.newPromise();
        // Attach a listener first to make the listener early handle a cause raised while writing headers
        // before any other callbacks like `onStreamClosed()` are invoked.
        promise.addListener(this);
        encoder.writeHeaders(id, streamId(), headers, headersOnly, promise);
    }

    static boolean needs100Continue(RequestHeaders headers) {
        return headers.contains(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString());
    }

    void handle100Continue(ResponseHeaders responseHeaders) {
        if (state != State.NEEDS_100_CONTINUE) {
            return;
        }

        if (responseHeaders.status() == HttpStatus.CONTINUE) {
            state = State.NEEDS_DATA_OR_TRAILERS;
            resume();
            // TODO(minwoox): reset the timeout
        } else {
            // We do not retry the request when HttpStatus.EXPECTATION_FAILED is received
            // because:
            // - Most servers support 100-continue.
            // - It's much simpler to just fail the request and let the user retry.
            state = State.DONE;
            logBuilder.endRequest();
            discardRequestBody();
        }
    }

    abstract void resume();

    abstract void discardRequestBody();

    /**
     * Writes the {@link HttpData} to the {@link Channel}.
     * Note that the written data is not flushed by this method. The caller should explicitly call
     * {@link Channel#flush()} when each write unit is done.
     */
    final void writeData(HttpData data) {
        data.touch(ctx);
        logBuilder.increaseRequestLength(data);
        write(data, data.isEndOfStream());
    }

    /**
     * Writes the {@link HttpHeaders trailers} to the {@link Channel}.
     * Note that the written data is not flushed by this method. The caller should explicitly call
     * {@link Channel#flush()} when each write unit is done.
     */
    final void writeTrailers(HttpHeaders trailers) {
        logBuilder.requestTrailers(trailers);
        write(trailers, true);
    }

    private void write(HttpObject o, boolean endOfStream) {
        if (!ch.isActive()) {
            PooledObjects.close(o);
            fail(newClosedSessionException(ch));
            return;
        }

        if (endOfStream) {
            state = State.DONE;
        }

        if (isStreamOrSessionClosed()) {
            PooledObjects.close(o);
            return;
        }

        final ChannelFuture future;
        if (o instanceof HttpHeaders) {
            future = encoder.writeTrailers(id, streamId(), (HttpHeaders) o);
        } else {
            future = encoder.writeData(id, streamId(), (HttpData) o, endOfStream);
        }

        future.addListener(this);
    }

    private boolean isStreamOrSessionClosed() {
        // Make sure that a stream exists before writing data if first bytes were transferred.
        // The following situation may cause the data to be written to a closed stream.
        // 1. A connection that has pending outbound buffers receives GOAWAY frame.
        // 2. AbstractHttp2ConnectionHandler.close() clears and flushes all active streams.
        // 3. After successfully flushing, operationComplete() requests next data and
        //    the subscriber attempts to write the next data to the stream closed at 2).
        if (!encoder.isWritable(id, streamId())) {
            if (ctx.sessionProtocol().isMultiplex()) {
                failAndReset(newClosedStreamException(ch));
            } else {
                failAndReset(newClosedSessionException(ch));
            }
            return true;
        }
        return false;
    }

    private int streamId() {
        return (id << 1) + 1;
    }

    final void failRequest(Throwable cause) {
        if (id() >= 0) {
            failAndReset(cause);
        } else {
            // No need to send RST because we didn't send any packet.
            fail(UnprocessedRequestException.of(cause));
        }
    }

    private void fail(Throwable cause) {
        if (failed) {
            return;
        }
        failed = true;
        state = State.DONE;
        cancel();
        logBuilder.endRequest(cause);
        if (responseWrapper != null) {
            if (responseWrapper.isOpen()) {
                responseWrapper.close(cause);
            } else {
                // To make it sure that the log is complete.
                logBuilder.endResponse(cause);
            }
        } else {
            logBuilder.endResponse(cause);
            originalRes.close(cause);
        }

        final CancellationScheduler scheduler = cancellationScheduler();
        if (scheduler != null) {
            // best-effort attempt to cancel the scheduled timeout task so that RequestContext#cause
            // isn't set unnecessarily
            scheduler.cancelScheduled();
        }
    }

    final void failAndReset(Throwable cause) {
        if (failed) {
            return;
        }

        if (cause instanceof WriteTimeoutException) {
            final HttpSession session = HttpSession.get(ch);
            // Mark the session as unhealthy so that subsequent requests do not use it.
            session.markUnacquirable();
        }

        if (cause instanceof ResponseCompleteException) {
            // - ResponseCompleteException means the response is successfully received.
            state = State.DONE;
            cancel();
            logBuilder.endRequest(cause);
        } else {
            fail(cause);
        }

        final Http2Error error;
        if (cause instanceof ResponseCompleteException || Exceptions.isStreamCancelling(cause)) {
            error = Http2Error.CANCEL;
        } else {
            error = Http2Error.INTERNAL_ERROR;
        }

        if (error.code() != Http2Error.CANCEL.code() && cause != ctx.cancellationCause()) {
            Exceptions.logIfUnexpected(logger, ch,
                                       HttpSession.get(ch).protocol(),
                                       "a request publisher raised an exception", cause);
        }

        if (ch.isActive()) {
            encoder.writeReset(id, streamId(), error);
            ch.flush();
        }
    }

    final boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        this.timeoutFuture = null;
        return timeoutFuture.cancel(false);
    }

    @Nullable
    private CancellationScheduler cancellationScheduler() {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt != null) {
            return ctxExt.responseCancellationScheduler();
        }
        return null;
    }
}
