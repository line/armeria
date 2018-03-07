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

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.AbstractRequestContext;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.ClosedPublisherException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.HttpObjectEncoder;
import com.linecorp.armeria.internal.logging.LoggingUtil;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

final class HttpRequestSubscriber implements Subscriber<HttpObject>, ChannelFutureListener {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestSubscriber.class);

    enum State {
        NEEDS_TO_WRITE_FIRST_HEADER,
        NEEDS_DATA_OR_TRAILING_HEADERS,
        DONE
    }

    private final ChannelHandlerContext ctx;
    private final HttpObjectEncoder encoder;
    private final int id;
    private final HttpRequest request;
    private final HttpResponseWrapper response;
    private final ClientRequestContext reqCtx;
    private final RequestLogBuilder logBuilder;
    private final long timeoutMillis;
    @Nullable
    private Subscription subscription;
    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.NEEDS_TO_WRITE_FIRST_HEADER;

    HttpRequestSubscriber(Channel ch, HttpObjectEncoder encoder,
                          int id, HttpRequest request, HttpResponseWrapper response,
                          ClientRequestContext reqCtx, long timeoutMillis) {

        ctx = ch.pipeline().lastContext();

        this.encoder = encoder;
        this.id = id;
        this.request = request;
        this.response = response;
        this.reqCtx = reqCtx;
        logBuilder = reqCtx.logBuilder();
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Invoked on each write of an {@link HttpObject}.
     */
    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
            if (state == State.DONE) {
                // Successfully sent the request; schedule the response timeout.
                response.scheduleTimeout(ctx);
            } else {
                assert subscription != null;
                subscription.request(1);
            }
            return;
        }

        fail(future.cause());

        final Throwable cause = future.cause();
        if (!(cause instanceof ClosedPublisherException)) {
            final Channel ch = future.channel();
            Exceptions.logIfUnexpected(logger, ch, HttpSession.get(ch).protocol(), cause);
            ch.close();
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;

        final EventLoop eventLoop = ctx.channel().eventLoop();
        if (timeoutMillis > 0) {
            timeoutFuture = eventLoop.schedule(
                    () -> {
                        if (state == State.NEEDS_TO_WRITE_FIRST_HEADER) {
                            if (reqCtx instanceof AbstractRequestContext) {
                                ((AbstractRequestContext) reqCtx).setTimedOut();
                            }
                            failAndRespond(WriteTimeoutException.get());
                        }
                    },
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }

        // NB: This must be invoked at the end of this method because otherwise the callback methods in this
        //     class can be called before the member fields (subscription and timeoutFuture) are initialized.
        //     It is because the successful write of the first headers will trigger subscription.request(1).
        eventLoop.execute(this::writeFirstHeader);
    }

    private void writeFirstHeader() {
        final Channel ch = ctx.channel();
        final HttpSession session = HttpSession.get(ch);
        if (!session.isActive()) {
            failAndRespond(ClosedSessionException.get());
            return;
        }

        final HttpHeaders firstHeaders = request.headers();
        final String host = LoggingUtil.remoteHost(firstHeaders, ch);

        final SessionProtocol protocol = session.protocol();
        assert protocol != null;
        logBuilder.startRequest(ch, protocol, host);
        logBuilder.requestHeaders(firstHeaders);

        if (request.isEmpty()) {
            setDone();
            write0(firstHeaders, true, true);
        } else {
            write0(firstHeaders, false, true);
        }
        state = State.NEEDS_DATA_OR_TRAILING_HEADERS;
        cancelTimeout();
    }

    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            throw newIllegalStateException(
                    "published an HttpObject that's neither Http2Headers nor Http2Data: " + o);
        }

        boolean endOfStream = o.isEndOfStream();
        switch (state) {
            case NEEDS_DATA_OR_TRAILING_HEADERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailingHeaders = (HttpHeaders) o;
                    if (trailingHeaders.status() != null) {
                        throw newIllegalStateException("published a trailing HttpHeaders with status: " + o);
                    }
                    // Trailing headers always end the stream even if not explicitly set.
                    endOfStream = true;
                }
                break;
            }
            case DONE:
                ReferenceCountUtil.safeRelease(o);
                return;
        }

        write(o, endOfStream, true);
    }

    @Override
    public void onError(Throwable cause) {
        failAndRespond(cause);
    }

    @Override
    public void onComplete() {
        if (!cancelTimeout()) {
            return;
        }

        if (state != State.DONE) {
            write(HttpData.EMPTY_DATA, true, true);
        }
    }

    private void write(HttpObject o, boolean endOfStream, boolean flush) {
        final Channel ch = ctx.channel();
        if (!ch.isActive()) {
            ReferenceCountUtil.safeRelease(o);
            fail(ClosedSessionException.get());
            return;
        }

        if (endOfStream) {
            setDone();
        }

        ch.eventLoop().execute(() -> write0(o, endOfStream, flush));
    }

    private void write0(HttpObject o, boolean endOfStream, boolean flush) {
        final ChannelFuture future;
        if (o instanceof HttpData) {
            final HttpData data = (HttpData) o;
            future = encoder.writeData(ctx, id, streamId(), data, endOfStream);
            logBuilder.increaseRequestLength(data.length());
        } else if (o instanceof HttpHeaders) {
            future = encoder.writeHeaders(ctx, id, streamId(), (HttpHeaders) o, endOfStream);
        } else {
            // Should never reach here because we did validation in onNext().
            throw new Error();
        }

        if (endOfStream) {
            logBuilder.endRequest();
        }

        future.addListener(this);
        if (flush) {
            ctx.flush();
        }

        if (state == State.DONE) {
            assert subscription != null;
            subscription.cancel();
        }
    }

    private int streamId() {
        return (id << 1) + 1;
    }

    private void fail(Throwable cause) {
        setDone();
        logBuilder.endRequest(cause);
        assert subscription != null;
        subscription.cancel();
    }

    private void setDone() {
        cancelTimeout();
        state = State.DONE;
    }

    private void failAndRespond(Throwable cause) {
        fail(cause);

        final Channel ch = ctx.channel();
        final Http2Error error;
        if (response.isOpen()) {
            response.close(cause);
            error = Http2Error.INTERNAL_ERROR;
        } else if (cause instanceof WriteTimeoutException || cause instanceof AbortedStreamException) {
            error = Http2Error.CANCEL;
        } else {
            Exceptions.logIfUnexpected(logger, ch,
                                       HttpSession.get(ch).protocol(),
                                       "a request publisher raised an exception", cause);
            error = Http2Error.INTERNAL_ERROR;
        }

        if (ch.isActive()) {
            encoder.writeReset(ctx, id, streamId(), error);
            ctx.flush();
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

    private IllegalStateException newIllegalStateException(String msg) {
        final IllegalStateException cause = new IllegalStateException(msg);
        fail(cause);
        return cause;
    }
}
