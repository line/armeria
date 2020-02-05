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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isTrailerBlacklisted;

import java.nio.channels.ClosedChannelException;
import java.util.Set;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.ClosedPublisherException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.internal.common.DefaultTimeoutController;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.HttpObjectEncoder;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

final class HttpResponseSubscriber extends DefaultTimeoutController implements Subscriber<HttpObject> {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseSubscriber.class);

    private static final AggregatedHttpResponse INTERNAL_SERVER_ERROR_MESSAGE =
            AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
    private static final AggregatedHttpResponse SERVICE_UNAVAILABLE_MESSAGE =
            AggregatedHttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);

    private static final Set<AsciiString> ADDITIONAL_HEADER_BLACKLIST = ImmutableSet.of(
            HttpHeaderNames.SCHEME, HttpHeaderNames.STATUS, HttpHeaderNames.METHOD, HttpHeaderNames.PATH);

    private static final String SERVER_HEADER =
            "Armeria/" + Version.get("armeria", HttpResponseSubscriber.class.getClassLoader())
                                .artifactVersion();

    enum State {
        NEEDS_HEADERS,
        NEEDS_DATA_OR_TRAILERS,
        DONE,
    }

    private final ChannelHandlerContext ctx;
    private final HttpObjectEncoder responseEncoder;
    private final DecodedHttpRequest req;
    private final DefaultServiceRequestContext reqCtx;
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;

    @Nullable
    private Subscription subscription;
    private State state = State.NEEDS_HEADERS;
    private boolean isComplete;

    private boolean loggedResponseHeadersFirstBytesTransferred;

    HttpResponseSubscriber(ChannelHandlerContext ctx, HttpObjectEncoder responseEncoder,
                           DefaultServiceRequestContext reqCtx, DecodedHttpRequest req,
                           boolean enableServerHeader, boolean enableDateHeader) {
        super(ctx.channel().eventLoop());
        this.ctx = ctx;
        this.responseEncoder = responseEncoder;
        this.req = req;
        this.reqCtx = reqCtx;
        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;
        setTimeoutTask(newTimeoutTask());
    }

    private HttpService service() {
        return reqCtx.service();
    }

    private RequestLogBuilder logBuilder() {
        return reqCtx.logBuilder();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;

        // Schedule the initial request timeout.
        final long requestTimeoutMillis = reqCtx.requestTimeoutMillis();
        if (requestTimeoutMillis > 0) {
            scheduleTimeout(requestTimeoutMillis);
        }

        // Start consuming.
        subscription.request(1);
    }

    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            throw newIllegalStateException(
                    "published an HttpObject that's neither HttpHeaders nor HttpData: " + o +
                    " (service: " + service() + ')');
        }

        boolean endOfStream = o.isEndOfStream();
        switch (state) {
            case NEEDS_HEADERS: {
                logBuilder().startResponse();
                if (!(o instanceof ResponseHeaders)) {
                    throw newIllegalStateException(
                            "published an HttpData without a preceding ResponseHeaders: " + o +
                            " (service: " + service() + ')');
                }

                ResponseHeaders headers = (ResponseHeaders) o;
                final HttpStatus status = headers.status();
                if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                    // Needs non-informational headers.
                    break;
                }

                if (req.method() == HttpMethod.HEAD || status.isContentAlwaysEmpty()) {
                    // We're done with the response if it is a response to a HEAD request or one of the
                    // no-content response statuses.
                    endOfStream = true;
                } else {
                    state = State.NEEDS_DATA_OR_TRAILERS;
                }

                final HttpHeaders additionalHeaders = reqCtx.additionalResponseHeaders();
                final HttpHeaders additionalTrailers = reqCtx.additionalResponseTrailers();

                final ResponseHeadersBuilder newHeaders = fillAdditionalHeaders(headers, additionalHeaders);

                if (endOfStream) {
                    fillAdditionalTrailers(newHeaders, additionalTrailers);
                }

                if (newHeaders.contains(HttpHeaderNames.CONTENT_LENGTH) &&
                    !additionalTrailers.isEmpty()) {
                    // We don't apply chunked encoding when the content-length header is set, which would
                    // prevent the trailers from being sent so we go ahead and remove content-length to force
                    // chunked encoding.
                    newHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
                }

                if (enableServerHeader && !newHeaders.contains(HttpHeaderNames.SERVER)) {
                    newHeaders.add(HttpHeaderNames.SERVER, SERVER_HEADER);
                }

                if (enableDateHeader && !newHeaders.contains(HttpHeaderNames.DATE)) {
                    newHeaders.add(HttpHeaderNames.DATE, HttpTimestampSupplier.currentTime());
                }

                headers = newHeaders.build();
                logBuilder().responseHeaders(headers);
                o = headers;

                break;
            }
            case NEEDS_DATA_OR_TRAILERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailers = (HttpHeaders) o;
                    if (trailers.contains(HttpHeaderNames.STATUS)) {
                        throw newIllegalStateException(
                                "published an HTTP trailers with status: " + o +
                                " (service: " + service() + ')');
                    }
                    final HttpHeaders additionalTrailers = reqCtx.additionalResponseTrailers();
                    final HttpHeaders addedTrailers = fillAdditionalTrailers(trailers, additionalTrailers);
                    logBuilder().responseTrailers(addedTrailers);
                    o = addedTrailers;

                    // Trailers always end the stream even if not explicitly set.
                    endOfStream = true;
                } else if (endOfStream) { // Last DATA frame
                    final HttpHeaders additionalTrailers = reqCtx.additionalResponseTrailers();
                    if (!additionalTrailers.isEmpty()) {
                        write(o, false);

                        o = additionalTrailers;
                    }
                }
                break;
            }
            case DONE:
                // Cancel the subscription if any message comes here after the state has been changed to DONE.
                assert subscription != null;
                subscription.cancel();
                ReferenceCountUtil.safeRelease(o);
                return;
        }

        write(o, endOfStream);
    }

    @Override
    public void onError(Throwable cause) {
        if (cause instanceof HttpResponseException) {
            // Timeout may occur when the aggregation of the error response takes long.
            // If timeout occurs, respond with 503 Service Unavailable.
            ((HttpResponseException) cause).httpResponse()
                                           .aggregate(ctx.executor())
                                           .handleAsync((res, throwable) -> {
                                               if (throwable != null) {
                                                   failAndRespond(throwable,
                                                                  INTERNAL_SERVER_ERROR_MESSAGE,
                                                                  Http2Error.CANCEL);
                                               } else {
                                                   failAndRespond(cause, res, Http2Error.CANCEL);
                                               }
                                               return null;
                                           }, ctx.executor());
        } else if (cause instanceof HttpStatusException) {
            failAndRespond(cause,
                           AggregatedHttpResponse.of(((HttpStatusException) cause).httpStatus()),
                           Http2Error.CANCEL);
        } else if (Exceptions.isStreamCancelling(cause)) {
            failAndReset(cause);
        } else {
            logger.warn("{} Unexpected exception from a service or a response publisher: {}",
                        ctx.channel(), service(), cause);

            failAndRespond(cause, INTERNAL_SERVER_ERROR_MESSAGE, Http2Error.INTERNAL_ERROR);
        }
    }

    @Override
    public void onComplete() {
        if (isTimedOut() && reqCtx.requestTimeoutHandler() == null) {
            // We have already returned a failed response due to a timeout.
            return;
        }

        cancelTimeout();

        if (wroteNothing(state)) {
            logger.warn("{} Published nothing (or only informational responses): {}", ctx.channel(), service());
            responseEncoder.writeReset(req.id(), req.streamId(), Http2Error.INTERNAL_ERROR);
            return;
        }

        if (state != State.DONE) {
            final HttpHeaders additionalTrailers =
                    fillAdditionalTrailers(HttpHeaders.of(), reqCtx.additionalResponseTrailers());
            if (!additionalTrailers.isEmpty()) {
                write(additionalTrailers, true);
            } else {
                write(HttpData.empty(), true);
            }
        }
    }

    private void write(HttpObject o, boolean endOfStream) {
        // Make sure that a stream exists before writing data if first bytes were transferred.
        // The following situation may cause the data to be written to a closed stream.
        // 1. A connection that has pending outbound buffers receives GOAWAY frame.
        // 2. AbstractHttp2ConnectionHandler.close() clears and flushes all active streams.
        // 3. After successfully flushing, the listener requests next data and
        //    the subscriber attempts to write the next data to the stream closed at 2).
        if (loggedResponseHeadersFirstBytesTransferred &&
            !responseEncoder.isWritable(req.id(), req.streamId())) {
            if (reqCtx.sessionProtocol().isMultiplex()) {
                fail(ClosedPublisherException.get());
            } else {
                fail(ClosedSessionException.get());
            }
            return;
        }

        if (endOfStream) {
            setDone();
        }

        final ChannelFuture future;
        final boolean wroteEmptyData;
        if (o instanceof HttpData) {
            final HttpData data = (HttpData) o;
            wroteEmptyData = data.isEmpty();
            logBuilder().increaseResponseLength(data);
            future = responseEncoder.writeData(req.id(), req.streamId(), data, endOfStream);
        } else if (o instanceof HttpHeaders) {
            wroteEmptyData = false;
            future = responseEncoder.writeHeaders(req.id(), req.streamId(), (HttpHeaders) o, endOfStream);
        } else {
            // Should never reach here because we did validation in onNext().
            throw new Error();
        }

        future.addListener((ChannelFuture f) -> {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                final boolean isSuccess;
                if (f.isSuccess()) {
                    isSuccess = true;
                } else {
                    // If 1) the last chunk we attempted to send was empty,
                    //    2) the connection has been closed,
                    //    3) and the protocol is HTTP/1,
                    // it is very likely that a client closed the connection after receiving the
                    // complete content, which is not really a problem.
                    isSuccess = endOfStream && wroteEmptyData &&
                                f.cause() instanceof ClosedChannelException &&
                                responseEncoder instanceof Http1ObjectEncoder;
                }

                // Write an access log if:
                // - every message has been sent successfully.
                // - any write operation is failed with a cause.
                if (isSuccess) {
                    maybeLogFirstResponseBytesTransferred();

                    if (endOfStream && tryComplete()) {
                        logBuilder().endResponse();
                        reqCtx.log().whenComplete().thenAccept(reqCtx.accessLogWriter()::log);
                    }

                    subscription.request(1);
                    return;
                }

                fail(f.cause());
                HttpServerHandler.CLOSE_ON_FAILURE.operationComplete(f);
            }
        });

        ctx.flush();
    }

    private State setDone() {
        cancelTimeout();
        final State oldState = state;
        state = State.DONE;
        return oldState;
    }

    private void fail(Throwable cause) {
        if (tryComplete()) {
            setDone();
            logBuilder().endResponse(cause);
            subscription.cancel();
            reqCtx.log().whenComplete().thenAccept(reqCtx.accessLogWriter()::log);
        }
    }

    private void failAndRespond(Throwable cause, AggregatedHttpResponse res, Http2Error error) {
        final ResponseHeaders headers = res.headers();
        final HttpData content = res.content();

        logBuilder().responseHeaders(headers);
        logBuilder().increaseResponseLength(content);

        final State oldState = setDone();
        subscription.cancel();

        final int id = req.id();
        final int streamId = req.streamId();

        final ChannelFuture future;
        if (wroteNothing(oldState)) {
            final ChannelFuture headersWriteFuture;
            // Did not write anything yet; we can send an error response instead of resetting the stream.
            if (content.isEmpty()) {
                headersWriteFuture = responseEncoder.writeHeaders(id, streamId, headers, true);
                future = headersWriteFuture;
            } else {
                headersWriteFuture = responseEncoder.writeHeaders(id, streamId, headers, false);
                future = responseEncoder.writeData(id, streamId, content, true);
            }

            headersWriteFuture.addListener((ChannelFuture f) -> {
                if (f.isSuccess()) {
                    maybeLogFirstResponseBytesTransferred();
                }
            });
        } else {
            // Wrote something already; we have to reset/cancel the stream.
            future = responseEncoder.writeReset(id, streamId, error);
        }

        addCallbackAndFlush(cause, oldState, future);
    }

    private void failAndReset(Throwable cause) {
        final State oldState = setDone();
        subscription.cancel();

        final ChannelFuture future =
                responseEncoder.writeReset(req.id(), req.streamId(), Http2Error.CANCEL);

        addCallbackAndFlush(cause, oldState, future);
    }

    private void addCallbackAndFlush(Throwable cause, State oldState, ChannelFuture future) {
        if (oldState != State.DONE) {
            future.addListener(unused -> {
                // Write an access log always with a cause. Respect the first specified cause.
                if (tryComplete()) {
                    try (SafeCloseable ignored = RequestContextUtil.pop()) {
                        logBuilder().endResponse(cause);
                        reqCtx.log().whenComplete().thenAccept(reqCtx.accessLogWriter()::log);
                    }
                }
            });
        }
        ctx.flush();
    }

    private boolean tryComplete() {
        if (isComplete) {
            return false;
        }
        isComplete = true;
        return true;
    }

    private void maybeLogFirstResponseBytesTransferred() {
        if (!loggedResponseHeadersFirstBytesTransferred) {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                logBuilder().responseFirstBytesTransferred();
            }
            loggedResponseHeadersFirstBytesTransferred = true;
        }
    }

    private static boolean wroteNothing(State state) {
        return state == State.NEEDS_HEADERS;
    }

    private static ResponseHeadersBuilder fillAdditionalHeaders(ResponseHeaders headers,
                                                                HttpHeaders additionalHeaders) {
        final ResponseHeadersBuilder builder = headers.toBuilder();
        if (!additionalHeaders.isEmpty()) {
            for (AsciiString name : additionalHeaders.names()) {
                if (!ADDITIONAL_HEADER_BLACKLIST.contains(name)) {
                    builder.remove(name);
                    additionalHeaders.forEachValue(name, value -> builder.add(name, value));
                }
            }
        }

        return builder;
    }

    private static HttpHeaders fillAdditionalTrailers(HttpHeaders trailers, HttpHeaders additionalTrailers) {
        if (additionalTrailers.isEmpty()) {
            return trailers;
        }

        return fillAdditionalTrailers(trailers.toBuilder(), additionalTrailers).build();
    }

    private static HttpHeadersBuilder fillAdditionalTrailers(HttpHeadersBuilder builder,
                                                             HttpHeaders additionalTrailers) {
        if (!additionalTrailers.isEmpty()) {
            for (AsciiString name : additionalTrailers.names()) {
                if (!ADDITIONAL_HEADER_BLACKLIST.contains(name) &&
                    !isTrailerBlacklisted(name)) {
                    builder.remove(name);
                    additionalTrailers.forEachValue(name, value -> builder.add(name, value));
                }
            }
        }

        return builder;
    }

    private IllegalStateException newIllegalStateException(String msg) {
        final IllegalStateException cause = new IllegalStateException(msg);
        failAndRespond(cause, INTERNAL_SERVER_ERROR_MESSAGE, Http2Error.INTERNAL_ERROR);
        return cause;
    }

    private TimeoutTask newTimeoutTask() {
        return new TimeoutTask() {
            @Override
            public boolean canSchedule() {
                return state != State.DONE;
            }

            @Override
            public void run() {
                if (state != State.DONE) {
                    reqCtx.setTimedOut();
                    final Runnable requestTimeoutHandler = reqCtx.requestTimeoutHandler();
                    if (requestTimeoutHandler != null) {
                        requestTimeoutHandler.run();
                    } else {
                        failAndRespond(RequestTimeoutException.get(),
                                       SERVICE_UNAVAILABLE_MESSAGE, Http2Error.INTERNAL_ERROR);
                    }
                }
            }
        };
    }
}
