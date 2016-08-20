/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http;

import static com.linecorp.armeria.server.http.HttpResponseSubscriber.State.NEEDS_DATA_OR_TRAILING_HEADERS;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpObject;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.http.HttpStatusClass;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;
import com.linecorp.armeria.internal.http.HttpObjectEncoder;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ResourceNotFoundException;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceUnavailableException;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Error;

final class HttpResponseSubscriber implements Subscriber<HttpObject>, ChannelFutureListener {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseSubscriber.class);

    enum State {
        NEEDS_HEADERS,
        NEEDS_DATA_OR_TRAILING_HEADERS,
        DONE,
    }

    private final ChannelHandlerContext ctx;
    private final HttpObjectEncoder responseEncoder;
    private final Service<?, ?> service;
    private final DecodedHttpRequest req;
    private final long timeoutMillis;
    private final ResponseLogBuilder logBuilder;

    private Subscription subscription;
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.NEEDS_HEADERS;

    HttpResponseSubscriber(ChannelHandlerContext ctx, HttpObjectEncoder responseEncoder,
                           ServiceRequestContext reqCtx, DecodedHttpRequest req) {
        this.ctx = ctx;
        this.responseEncoder = responseEncoder;
        this.req = req;

        service = reqCtx.service();
        logBuilder = reqCtx.responseLogBuilder();
        timeoutMillis = reqCtx.requestTimeoutMillis();
    }

    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
            if (state != State.DONE) {
                subscription.request(1);
            }
            return;
        }

        fail(future.cause());
        HttpServerHandler.CLOSE_ON_FAILURE.operationComplete(future);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;

        if (timeoutMillis > 0) {
            timeoutFuture = ctx.channel().eventLoop().schedule(
                    () -> {
                        if (state != State.DONE) {
                            failAndRespond(RequestTimeoutException.get(),
                                           HttpStatus.SERVICE_UNAVAILABLE, Http2Error.INTERNAL_ERROR);
                        }
                    },
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }

        subscription.request(1);
    }

    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            throw newIllegalStateException(
                    "published an HttpObject that's neither HttpHeaders nor HttpData: " + o +
                    " (service: " + service + ')');
        }

        boolean endOfStream = false;
        switch (state) {
            case NEEDS_HEADERS: {
                logBuilder.start();
                if (!(o instanceof HttpHeaders)) {
                    throw newIllegalStateException(
                            "published an HttpData without a preceding Http2Headers: " + o +
                            " (service: " + service + ')');
                }

                final HttpHeaders headers = (HttpHeaders) o;
                final HttpStatus status = headers.status();
                if (status == null) {
                    throw newIllegalStateException("published an HttpHeaders without status: " + o +
                                                   " (service: " + service + ')');
                }

                if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                    // Needs non-informational headers.
                    break;
                }

                final int statusCode = status.code();
                logBuilder.statusCode(statusCode);
                logBuilder.attr(ResponseLog.HTTP_HEADERS).set(headers);

                if (req.method() == HttpMethod.HEAD) {
                    endOfStream = true;
                    break;
                }

                switch (statusCode) {
                    case 204: case 205: case 304:
                        endOfStream = true;
                        break;
                    default:
                        state = NEEDS_DATA_OR_TRAILING_HEADERS;
                }
                break;
            }
            case NEEDS_DATA_OR_TRAILING_HEADERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailingHeaders = (HttpHeaders) o;
                    if (trailingHeaders.status() != null) {
                        throw newIllegalStateException(
                                "published a trailing HttpHeaders with status: " + o +
                                " (service: " + service + ')');
                    }

                    endOfStream = true;
                }
                break;
            }
            case DONE:
                return;
        }

        write(o, endOfStream, true);
    }

    @Override
    public void onError(Throwable cause) {
        if (cause instanceof ServiceUnavailableException) {
            failAndRespond(cause, HttpStatus.SERVICE_UNAVAILABLE, Http2Error.CANCEL);
        } else if (cause instanceof ResourceNotFoundException) {
            failAndRespond(cause, HttpStatus.NOT_FOUND, Http2Error.CANCEL);
        } else {
            logger.warn("{} Unexpected exception from a service or a response publisher: {}",
                        ctx.channel(), service, cause);

            failAndRespond(cause, HttpStatus.INTERNAL_SERVER_ERROR, Http2Error.INTERNAL_ERROR);
        }
    }

    @Override
    public void onComplete() {
        if (!cancelTimeout()) {
            return;
        }

        if (wroteNothing(state)) {
            logger.warn("{} Published nothing (or only informational responses): {}", ctx.channel(), service);
            responseEncoder.writeReset(ctx, req.id(), req.streamId(), Http2Error.INTERNAL_ERROR);
            return;
        }

        if (state != State.DONE) {
            write(HttpData.EMPTY_DATA, true, true);
        }
    }

    private void write(HttpObject o, boolean endOfStream, boolean flush) {
        if (state == State.DONE) {
            throw newIllegalStateException(
                    "a response publisher published an HttpObject after a trailing HttpHeaders: " + o);
        }

        final Channel ch = ctx.channel();
        if (!ch.isActive()) {
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
            future = responseEncoder.writeData(ctx, req.id(), req.streamId(), data, endOfStream);
            logBuilder.increaseContentLength(data.length());
        } else if (o instanceof HttpHeaders) {
            future = responseEncoder.writeHeaders(ctx, req.id(), req.streamId(), (HttpHeaders) o, endOfStream);
        } else {
            // Should never reach here because we did validation in onNext().
            throw new Error();
        }

        if (endOfStream) {
            logBuilder.end();
        }

        future.addListener(this);
        if (flush) {
            ctx.flush();
        }
    }

    private void fail(Throwable cause) {
        setDone();
        logBuilder.end(cause);
    }

    private void setDone() {
        cancelTimeout();
        state = State.DONE;
        subscription.cancel();
    }

    private void failAndRespond(Throwable cause, HttpStatus status, Http2Error error) {
        final State state = this.state;
        fail(cause);

        final int id = req.id();
        final int streamId = req.streamId();

        if (wroteNothing(state)) {
            // Did not write anything yet; we can send an error response instead of resetting the stream.
            final HttpData content = status.toHttpData();
            responseEncoder.writeHeaders(
                    ctx, id, streamId,
                    HttpHeaders.of(status)
                               .set(HttpHeaderNames.CONTENT_TYPE,
                                    MediaType.PLAIN_TEXT_UTF_8.toString())
                               .setInt(HttpHeaderNames.CONTENT_LENGTH, content.length()),
                    false);
            responseEncoder.writeData(ctx, id, streamId, content, true);
        } else {
            // Wrote something already; we have to reset/cancel the stream.
            responseEncoder.writeReset(ctx, id, streamId, error);
        }
        ctx.flush();
    }

    private static boolean wroteNothing(State state) {
        return state == State.NEEDS_HEADERS;
    }

    private boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        return timeoutFuture.cancel(false);
    }

    private IllegalStateException newIllegalStateException(String msg) {
        final IllegalStateException cause = new IllegalStateException(msg);
        failAndRespond(cause, HttpStatus.INTERNAL_SERVER_ERROR, Http2Error.INTERNAL_ERROR);
        return cause;
    }
}
