/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AbstractHttpToHttp2ConnectionHandler;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceCodec.DecodeResult;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

final class HttpServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);

    private static final AsciiString STREAM_ID = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text();
    private static final AsciiString ERROR_CONTENT_TYPE = new AsciiString("text/plain; charset=UTF-8");
    private static final AsciiString ALLOWED_METHODS =
            new AsciiString("DELETE,GET,HEAD,OPTIONS,PATCH,POST,PUT,TRACE");

    private static final ChannelFutureListener CLOSE = future -> {
        final Throwable cause = future.cause();
        final Channel ch = future.channel();
        if (cause != null) {
            Exceptions.logIfUnexpected(logger, ch, protocol(ch), cause);
        }
        safeClose(ch);
    };

    private static final ChannelFutureListener CLOSE_ON_FAILURE = future -> {
        final Throwable cause = future.cause();
        if (cause != null) {
            final Channel ch = future.channel();
            Exceptions.logIfUnexpected(logger, ch, protocol(ch), cause);
            safeClose(ch);
        }
    };

    private static SessionProtocol protocol(Channel ch) {
        final HttpServerHandler handler = ch.pipeline().get(HttpServerHandler.class);
        final SessionProtocol protocol;
        if (handler != null) {
            protocol = handler.protocol;
        } else {
            protocol = null;
        }
        return protocol;
    }

    static void safeClose(Channel ch) {
        if (!ch.isActive()) {
            return;
        }

        // Do not call Channel.close() if AbstractHttpToHttp2ConnectionHandler.close() has been invoked
        // already. Otherwise, it can trigger a bad cycle:
        //
        //   1. Channel.close() triggers AbstractHttpToHttp2ConnectionHandler.close().
        //   2. AbstractHttpToHttp2ConnectionHandler.close() triggers Http2Stream.close().
        //   3. Http2Stream.close() fails the promise of its pending writes.
        //   4. The failed promise notifies this listener (CLOSE_ON_FAILURE).
        //   5. This listener calls Channel.close().
        //   6. Repeat from the step 1.
        //

        final AbstractHttpToHttp2ConnectionHandler h2handler =
                ch.pipeline().get(AbstractHttpToHttp2ConnectionHandler.class);

        if (h2handler == null || !h2handler.isClosing()) {
            ch.close();
        }
    }

    @SuppressWarnings("ThrowableInstanceNeverThrown")
    private static final Exception SERVICE_NOT_FOUND = new ServiceNotFoundException();

    private final ServerConfig config;
    private SessionProtocol protocol;
    private Http2Connection http2conn;

    private boolean isReading;

    // When head-of-line blocking is enabled (i.e. HTTP/1), we assign a monotonically increasing integer
    // ('request sequence') to each received request, and assign the integer of the same value
    // when creating its response.

    /**
     * The request sequence of the most recently received request.
     * Incremented when a new request is received.
     */
    private int reqSeq;
    /**
     * The request sequence of the request which was received least recently and has no corresponding response.
     */
    private int resSeq;

    /**
     * The map which maps a sequence number to its related pending response.
     */
    private final IntObjectMap<FullHttpResponse> pendingResponses = new IntObjectHashMap<>();

    private boolean handledLastRequest;

    HttpServerHandler(ServerConfig config, SessionProtocol protocol) {
        assert protocol == SessionProtocol.H1 ||
               protocol == SessionProtocol.H1C ||
               protocol == SessionProtocol.H2;

        this.config = requireNonNull(config, "config");
        this.protocol = requireNonNull(protocol, "protocol");
    }

    private boolean isHttp2() {
        return http2conn != null;
    }

    private void setHttp2(ChannelHandlerContext ctx) {
        switch (protocol) {
            case H1:
                protocol = SessionProtocol.H2;
                break;
            case H1C:
                protocol = SessionProtocol.H2C;
                break;
        }

        final Http2ConnectionHandler handler = ctx.pipeline().get(Http2ConnectionHandler.class);
        http2conn = handler.connection();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        isReading = true; // Cleared in channelReadComplete()

        if (msg instanceof Http2Settings) {
            handleHttp2Settings(ctx, (Http2Settings) msg);
        } else {
            handleRequest(ctx, (FullHttpRequest) msg);
        }
    }

    private void handleHttp2Settings(ChannelHandlerContext ctx, Http2Settings h2settings) {
        logger.debug("{} HTTP/2 settings: {}", ctx.channel(), h2settings);
        setHttp2(ctx);
    }

    private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        // Ignore the request received after the last request,
        // because we are going to close the connection after sending the last response.
        if (handledLastRequest) {
            return;
        }

        boolean invoked = false;
        try {
            // If we received the message with keep-alive disabled,
            // we should not accept a request anymore.
            if (!HttpUtil.isKeepAlive(req)) {
                handledLastRequest = true;
            }

            final int reqSeq = this.reqSeq++;

            if (!req.decoderResult().isSuccess()) {
                respond(ctx, reqSeq, req, HttpResponseStatus.BAD_REQUEST, req.decoderResult().cause());
                return;
            }

            if (req.method() == HttpMethod.CONNECT) {
                respond(ctx, reqSeq, req, HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            final String path = stripQuery(req.uri());
            // Reject requests without a valid path, except for the special 'OPTIONS *' request.
            if (path.isEmpty() || path.charAt(0) != '/') {
                if (req.method() == HttpMethod.OPTIONS && "*".equals(path)) {
                    handleOptions(ctx, req, reqSeq);
                } else {
                    respond(ctx, reqSeq, req, HttpResponseStatus.BAD_REQUEST);
                }
                return;
            }

            final String hostname = hostname(req);
            final VirtualHost host = config.findVirtualHost(hostname);

            // Find the service that matches the path.
            final PathMapped<ServiceConfig> mapped = host.findServiceConfig(path);
            if (!mapped.isPresent()) {
                // No services matched the path.
                handleNonExistentMapping(ctx, reqSeq, req, host, path);
                return;
            }

            // Decode the request and create a new invocation context from it to perform an invocation.
            final String mappedPath = mapped.mappedPath();
            final ServiceConfig serviceCfg = mapped.value();
            final Service service = serviceCfg.service();
            final ServiceCodec codec = service.codec();
            final Promise<Object> promise = ctx.executor().newPromise();
            final DecodeResult decodeResult = codec.decodeRequest(
                    serviceCfg, ctx.channel(), protocol,
                    hostname, path, mappedPath, req.content(), req, promise);

            switch (decodeResult.type()) {
            case SUCCESS: {
                // A successful decode; perform the invocation.
                final ServiceInvocationContext iCtx = decodeResult.invocationContext();
                invoke(iCtx, service.handler(), promise);
                invoked = true;

                // Do the post-invocation tasks such as scheduling a timeout.
                handleInvocationPromise(ctx, reqSeq, req, codec, iCtx, promise);
                break;
            }
            case FAILURE: {
                // Could not create an invocation context.
                handleDecodeFailure(ctx, reqSeq, req, decodeResult, promise);
                break;
            }
            case NOT_FOUND:
                // Turned out that the request wasn't accepted by the matching service.
                promise.tryFailure(SERVICE_NOT_FOUND);
                respond(ctx, reqSeq, req, HttpResponseStatus.NOT_FOUND);
                break;
            }
        } finally {
            // If invocation has been started successfully, handleInvocationResult() will call
            // ReferenceCountUtil.safeRelease() when the invocation is done.
            if (!invoked) {
                ReferenceCountUtil.safeRelease(req);
            }
        }
    }

    private void handleOptions(ChannelHandlerContext ctx, FullHttpRequest req, int reqSeq) {
        final FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        res.headers().set(HttpHeaderNames.ALLOW, ALLOWED_METHODS);
        respond(ctx, reqSeq, req, res);
    }

    private void handleNonExistentMapping(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                                          VirtualHost host, String path) {

        if (path.charAt(path.length() - 1) != '/') {
            // Handle the case where /path doesn't exist but /path/ exists.
            final String pathWithSlash = path + '/';
            if (host.findServiceConfig(pathWithSlash).isPresent()) {
                final String location;
                if (path.length() == req.uri().length()) {
                    location = pathWithSlash;
                } else {
                    location = pathWithSlash + req.uri().substring(path.length());
                }
                redirect(ctx, reqSeq, req, location);
                return;
            }
        }

        respond(ctx, reqSeq, req, HttpResponseStatus.NOT_FOUND);
    }

    private void invoke(ServiceInvocationContext iCtx, ServiceInvocationHandler handler,
                        Promise<Object> promise) {

        ServiceInvocationContext.setCurrent(iCtx);
        try {
            handler.invoke(iCtx, config.blockingTaskExecutor(), promise);
        } catch (Throwable t) {
            if (!promise.tryFailure(t)) {
                logger.warn("{} invoke() failed with a finished promise: {}", iCtx, promise, t);
            }
        } finally {
            ServiceInvocationContext.removeCurrent();
        }
    }

    private void handleInvocationPromise(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                                         ServiceCodec codec, ServiceInvocationContext iCtx,
                                         Promise<Object> promise) throws Exception {
        if (promise.isDone()) {
            // If the invocation has been finished immediately,
            // there's no need to schedule a timeout nor to add a listener to the promise.
            handleInvocationResult(ctx, reqSeq, req, iCtx, codec, promise, null);
        } else {
            final long timeoutMillis = config.requestTimeoutPolicy().timeout(iCtx);
            final ScheduledFuture<?> timeoutFuture;
            if (timeoutMillis > 0) {
                timeoutFuture = ctx.executor().schedule(
                        () -> promise.tryFailure(new RequestTimeoutException(
                                "request timed out after " + timeoutMillis + "ms: " + iCtx)),
                        timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                timeoutFuture = null;
            }

            promise.addListener((Future<Object> future) -> {
                try {
                    handleInvocationResult(ctx, reqSeq, req, iCtx, codec, future, timeoutFuture);
                } catch (Exception e) {
                    respond(ctx, reqSeq, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, e);
                }
            });
        }
    }

    private void handleInvocationResult(
            ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
            ServiceInvocationContext iCtx, ServiceCodec codec, Future<Object> future,
            ScheduledFuture<?> timeoutFuture) throws Exception {

        // Release the original request which was retained before the invocation.
        ReferenceCountUtil.safeRelease(req);

        // Cancel the associated timeout, if any.
        if (timeoutFuture != null) {
            timeoutFuture.cancel(true);
        }

        // No need to build the HTTP response if the connection/stream has been closed.
        if (isStreamClosed(ctx, req)) {
            if (future.isSuccess()) {
                ReferenceCountUtil.safeRelease(future.getNow());
            }
            return;
        }

        if (future.isSuccess()) {
            final Object res = future.getNow();
            if (res instanceof FullHttpResponse) {
                respond(ctx, reqSeq, req, (FullHttpResponse) res);
            } else {
                final ByteBuf encoded = codec.encodeResponse(iCtx, res);
                respond(ctx, reqSeq, req, encoded);
            }
        } else {
            final Throwable cause = future.cause();
            final ByteBuf encoded = codec.encodeFailureResponse(iCtx, cause);
            if (codec.failureResponseFailsSession(iCtx)) {
                respond(ctx, reqSeq, req, toHttpResponseStatus(cause), encoded);
            } else {
                respond(ctx, reqSeq, req, encoded);
            }
        }
    }

    private boolean isStreamClosed(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!ctx.channel().isActive()) {
            // Connection has been closed.
            return true;
        }

        final Http2Connection http2conn = this.http2conn;
        if (http2conn == null) {
            // HTTP/1 connection
            return false;
        }

        final Integer streamId = req.headers().getInt(STREAM_ID);
        if (streamId == null) {
            throw new IllegalStateException("An HTTP/2 request does not have a stream ID: " + req);
        }

        final Http2Stream stream = http2conn.stream(streamId);
        if (stream == null) {
            // The stream has been closed and removed.
            return true;
        }

        final State state = stream.state();
        return state == State.CLOSED || state == State.HALF_CLOSED_LOCAL;
    }

    private void handleDecodeFailure(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                                     DecodeResult decodeResult, Promise<Object> promise) {
        final Object errorResponse = decodeResult.errorResponse();
        if (errorResponse instanceof FullHttpResponse) {
            FullHttpResponse httpResponse = (FullHttpResponse) errorResponse;
            promise.tryFailure(new RequestDecodeException(
                    decodeResult.cause(), httpResponse.content().readableBytes()));
            respond(ctx, reqSeq, req, (FullHttpResponse) errorResponse);
        } else {
            ReferenceCountUtil.safeRelease(errorResponse);
            promise.tryFailure(new RequestDecodeException(decodeResult.cause(), 0));
            respond(ctx, reqSeq, req, HttpResponseStatus.BAD_REQUEST, decodeResult.cause());
        }
    }

    private static String hostname(FullHttpRequest req) {
        final String hostname = req.headers().getAsString(HttpHeaderNames.HOST);
        if (hostname == null) {
            return "";
        }

        final int hostnameColonIdx = hostname.lastIndexOf(':');
        if (hostnameColonIdx < 0) {
            return hostname;
        }

        return hostname.substring(0, hostnameColonIdx);
    }

    private static String stripQuery(String uri) {
        final int queryStart = uri.indexOf('?');
        return queryStart < 0 ? uri : uri.substring(0, queryStart);
    }

    private static HttpResponseStatus toHttpResponseStatus(Throwable cause) {
        if (cause instanceof RequestTimeoutException || cause instanceof ServiceUnavailableException) {
            return HttpResponseStatus.SERVICE_UNAVAILABLE;
        }

        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, ByteBuf content) {
        respond(ctx, reqSeq, req, HttpResponseStatus.OK, content);
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                         HttpResponseStatus status, ByteBuf content) {

        if (content == null) {
            content = Unpooled.EMPTY_BUFFER;
        }
        respond(ctx, reqSeq, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content));
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                         HttpResponseStatus status) {

        if (status.code() < 400) {
            respond(ctx, reqSeq, req, status, Unpooled.EMPTY_BUFFER);
        } else {
            respond(ctx, reqSeq, req, status, (Throwable) null);
        }
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                         HttpResponseStatus status, Throwable cause) {

        assert status.code() >= 400;

        final ByteBuf content;
        if (req.method() == HttpMethod.HEAD) {
            // A response to a HEAD request must have no content.
            content = Unpooled.EMPTY_BUFFER;
            if (cause != null) {
                Exceptions.logIfUnexpected(logger, ctx.channel(), protocol, errorMessage(status), cause);
            }
        } else {
            final String msg = errorMessage(status);
            if (cause != null) {
                Exceptions.logIfUnexpected(logger, ctx.channel(), protocol, msg, cause);
            }
            content = Unpooled.copiedBuffer(msg, StandardCharsets.UTF_8);
        }

        final DefaultFullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, ERROR_CONTENT_TYPE);

        respond(ctx, reqSeq, req, res);
    }

    private void redirect(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, String location) {
        final DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.TEMPORARY_REDIRECT, Unpooled.EMPTY_BUFFER);
        res.headers().set(HttpHeaderNames.LOCATION, location);

        respond(ctx, reqSeq, req, res);
    }

    private static String errorMessage(HttpResponseStatus status) {
        String reasonPhrase = status.reasonPhrase();
        StringBuilder buf = new StringBuilder(reasonPhrase.length() + 4);

        buf.append(status.code());
        buf.append(' ');
        buf.append(reasonPhrase);
        return buf.toString();
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, FullHttpResponse res) {
        if (isHttp2()) {
            final String streamId = req.headers().getAsString(STREAM_ID);
            res.headers().set(STREAM_ID, streamId);
        } else if (!handlePendingResponses(ctx, reqSeq, req, res)) {
            // HTTP/1 and the responses for the previous requests are not all ready.
            return;
        }

        if (!handledLastRequest) {
            addKeepAliveHeaders(req, res);
            ctx.write(res).addListener(CLOSE_ON_FAILURE);
        } else {
            // Note that it is perfectly fine not to set the 'content-length' header to the last response
            // of an HTTP/1 connection. We just set it to work around overly strict HTTP clients that always
            // require a 'content-length' header for non-chunked responses.
            setContentLength(req, res);
            ctx.write(res).addListener(CLOSE);
        }

        if (!isReading) {
            ctx.flush();
        }
    }

    private boolean handlePendingResponses(
            ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, FullHttpResponse res) {

        final IntObjectMap<FullHttpResponse> pendingResponses = this.pendingResponses;
        while (reqSeq != resSeq) {
            FullHttpResponse pendingRes = pendingResponses.remove(resSeq);
            if (pendingRes == null) {
                // Stuck by head-of-line blocking; try again later.
                addKeepAliveHeaders(req, res);
                FullHttpResponse oldPendingRes = pendingResponses.put(reqSeq, res);
                if (oldPendingRes != null) {
                    // It is impossible to reach here as long as there are 2G+ pending responses.
                    logger.error("{} Orphaned pending response ({}): {}", reqSeq, oldPendingRes);
                    ReferenceCountUtil.safeRelease(oldPendingRes.release());
                }
                return false;
            }

            ctx.write(pendingRes);
            resSeq++;
        }

        // At this point, we have cleared all the pending responses. i.e. reqSeq = resSeq
        // Increment resSeq in preparation of the next request.
        resSeq++;
        return true;
    }

    /**
     * Sets the keep alive header as per:
     * - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
     */
    private static void addKeepAliveHeaders(FullHttpRequest req, FullHttpResponse res) {
        res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        setContentLength(req, res);
    }

    /**
     * Sets the 'content-length' header to the response.
     */
    private static void setContentLength(FullHttpRequest req, FullHttpResponse res) {
        final int statusCode = res.status().code();
        // http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.4
        // prohibits to send message body for below cases.
        // and in those cases, content-length should not be sent.
        if (statusCode < 200 || statusCode == 204 || statusCode == 304 || req.method() == HttpMethod.HEAD) {
            return;
        }
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        isReading = false;
        ctx.flush();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof UpgradeEvent) {
            assert !isReading;

            // Upgraded to HTTP/2.
            setHttp2(ctx);

            // Continue handling the upgrade request after the upgrade is complete.
            final FullHttpRequest req = ((UpgradeEvent) evt).upgradeRequest();

            // Remove the headers related with the upgrade.
            req.headers().remove(HttpHeaderNames.CONNECTION);
            req.headers().remove(HttpHeaderNames.UPGRADE);
            req.headers().remove(Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER);

            logger.debug("{} Handling the pre-upgrade request ({}): {}",
                         ctx.channel(), ((UpgradeEvent) evt).protocol(), req);

            // Set the stream ID of the pre-upgrade request, which is always 1.
            req.headers().set(STREAM_ID, "1");

            channelRead(ctx, req);
            channelReadComplete(ctx);
            return;
        }

        logger.warn("{} Unexpected user event: {}", ctx.channel(), evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Exceptions.logIfUnexpected(logger, ctx.channel(), protocol, cause);
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }
}
