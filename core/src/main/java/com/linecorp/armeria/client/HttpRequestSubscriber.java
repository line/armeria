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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.HttpSessionHandler.MAX_NUM_REQUESTS_SENT;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.mergeRequestHeaders;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.proxy.ProxyConnectException;

final class HttpRequestSubscriber implements Subscriber<HttpObject>, ChannelFutureListener {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestSubscriber.class);

    enum State {
        NEEDS_TO_WRITE_FIRST_HEADER,
        NEEDS_DATA_OR_TRAILERS,
        DONE
    }

    private final Channel ch;
    private final ClientHttpObjectEncoder encoder;
    private final HttpResponseDecoder responseDecoder;
    private final HttpRequest request;
    private final DecodedHttpResponse originalRes;
    private final ClientRequestContext ctx;
    private final RequestLogBuilder logBuilder;
    private final long timeoutMillis;

    // subscription, id and responseWrapper are assigned in onSubscribe()
    @Nullable
    private Subscription subscription;
    private int id = -1;
    @Nullable
    private HttpResponseWrapper responseWrapper;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.NEEDS_TO_WRITE_FIRST_HEADER;
    private boolean isSubscriptionCompleted;
    private boolean loggedRequestFirstBytesTransferred;

    HttpRequestSubscriber(Channel ch, ClientHttpObjectEncoder encoder, HttpResponseDecoder responseDecoder,
                          HttpRequest request, DecodedHttpResponse originalRes,
                          ClientRequestContext ctx, long timeoutMillis) {
        this.ch = ch;
        this.encoder = encoder;
        this.responseDecoder = responseDecoder;
        this.request = request;
        this.originalRes = originalRes;
        this.ctx = ctx;
        logBuilder = ctx.logBuilder();
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Invoked on each write of an {@link HttpObject}.
     */
    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
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

                // Request more messages regardless whether the state is DONE. It makes the producer have
                // a chance to produce the last call such as 'onComplete' and 'onError' when there are
                // no more messages it can produce.
                if (!isSubscriptionCompleted) {
                    assert subscription != null;
                    subscription.request(1);
                }
                return;
            }

            if (!loggedRequestFirstBytesTransferred) {
                fail(UnprocessedRequestException.of(future.cause()));
            } else {
                failAndReset(future.cause());
            }
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;
        if (state == State.DONE) {
            cancelSubscription();
            return;
        }

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
                        "Can't send requests. ID: " + id + ", session active: " + session.isActive() +
                        ", response needs to disconnect: " + responseDecoder.needsToDisconnectWhenFinished());
            }
            responseDecoder.disconnectWhenFinished();
            // No need to send RST because we didn't send any packet and this will be disconnected anyway.
            fail(UnprocessedRequestException.of(exception));
            return;
        }

        addResponseToDecoder();
        if (timeoutMillis > 0) {
            // The timer would be executed if the first message has not been sent out within the timeout.
            timeoutFuture = ch.eventLoop().schedule(
                    () -> failAndReset(WriteTimeoutException.get()),
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }

        // NB: This must be invoked at the end of this method because otherwise the callback methods in this
        //     class can be called before the member fields (subscription, id, responseWrapper and
        //     timeoutFuture) are initialized.
        //     It is because the successful write of the first headers will trigger subscription.request(1).
        writeFirstHeader(session);
    }

    private void addResponseToDecoder() {
        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        final long maxContentLength = ctx.maxResponseLength();
        responseWrapper = responseDecoder.addResponse(id, originalRes, ctx,
                                                      ch.eventLoop(), responseTimeoutMillis, maxContentLength);
    }

    private void writeFirstHeader(HttpSession session) {
        final RequestHeaders firstHeaders = request.headers();

        final SessionProtocol protocol = session.protocol();
        assert protocol != null;
        if (request.isEmpty()) {
            state = State.DONE;
        } else {
            state = State.NEEDS_DATA_OR_TRAILERS;
        }

        final RequestHeaders merged = mergeRequestHeaders(firstHeaders, ctx.additionalRequestHeaders());
        logBuilder.requestHeaders(firstHeaders);
        final ChannelFuture future = encoder.writeHeaders(id, streamId(), merged, request.isEmpty());
        future.addListener(this);
        ch.flush();
    }

    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            failAndReset(new IllegalArgumentException(
                    "published an HttpObject that's neither Http2Headers nor Http2Data: " + o));
            return;
        }

        boolean endOfStream = o.isEndOfStream();
        switch (state) {
            case NEEDS_DATA_OR_TRAILERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailers = (HttpHeaders) o;
                    if (trailers.contains(HttpHeaderNames.STATUS)) {
                        failAndReset(new IllegalArgumentException("published a trailers with status: " + o));
                        return;
                    }
                    // Trailers always end the stream even if not explicitly set.
                    endOfStream = true;
                    logBuilder.requestTrailers(trailers);
                } else {
                    logBuilder.increaseRequestLength((HttpData) o);
                }
                write(o, endOfStream);
                break;
            }
            case DONE:
                // Cancel the subscription if any message comes here after the state has been changed to DONE.
                cancelSubscription();
                PooledObjects.close(o);
                break;
        }
    }

    @Override
    public void onError(Throwable cause) {
        isSubscriptionCompleted = true;
        if (id >= 0) { // onSubscribe is called.
            failAndReset(cause);
        } else {
            // No need to send RST because we didn't send any packet.
            fail(UnprocessedRequestException.of(cause));
        }
    }

    @Override
    public void onComplete() {
        isSubscriptionCompleted = true;
        cancelTimeout();

        if (state != State.DONE) {
            write(HttpData.empty(), true);
        }
    }

    private void write(HttpObject o, boolean endOfStream) {
        if (!ch.isActive()) {
            PooledObjects.close(o);
            fail(ClosedSessionException.get());
            return;
        }

        if (endOfStream) {
            state = State.DONE;
        }

        if (isStreamOrSessionClosed()) {
            return;
        }

        final ChannelFuture future;
        if (o instanceof HttpHeaders) {
            future = encoder.writeTrailers(id, streamId(), (HttpHeaders) o);
        } else {
            future = encoder.writeData(id, streamId(), (HttpData) o, endOfStream);
        }

        future.addListener(this);
        ch.flush();
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
                failAndReset(ClosedStreamException.get());
            } else {
                failAndReset(ClosedSessionException.get());
            }
            return true;
        }
        return false;
    }

    private int streamId() {
        return (id << 1) + 1;
    }

    private void fail(Throwable cause) {
        state = State.DONE;
        cancelSubscription();
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
    }

    private void cancelSubscription() {
        isSubscriptionCompleted = true;
        assert subscription != null;
        subscription.cancel();
    }

    private void failAndReset(Throwable cause) {
        if (cause instanceof ProxyConnectException) {
            // ProxyConnectException is handled by HttpSessionHandler.exceptionCaught().
            return;
        }

        fail(cause);

        final Http2Error error;
        if (Exceptions.isStreamCancelling(cause)) {
            error = Http2Error.CANCEL;
        } else {
            error = Http2Error.INTERNAL_ERROR;
        }

        if (error.code() != Http2Error.CANCEL.code()) {
            Exceptions.logIfUnexpected(logger, ch,
                                       HttpSession.get(ch).protocol(),
                                       "a request publisher raised an exception", cause);
        }

        if (ch.isActive()) {
            encoder.writeReset(id, streamId(), error);
            ch.flush();
        }
    }

    private boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        this.timeoutFuture = null;
        return timeoutFuture.cancel(false);
    }
}
