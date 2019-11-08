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
import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isCorsPreflightRequest;
import static com.linecorp.armeria.server.HttpHeaderUtil.determineClientAddress;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.stream.ClosedPublisherException;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.ChannelUtil;
import com.linecorp.armeria.internal.Http1ObjectEncoder;
import com.linecorp.armeria.internal.Http2ObjectEncoder;
import com.linecorp.armeria.internal.HttpObjectEncoder;
import com.linecorp.armeria.internal.PathAndQuery;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

final class HttpServerHandler extends ChannelInboundHandlerAdapter implements HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);

    private static final MediaType ERROR_CONTENT_TYPE = MediaType.PLAIN_TEXT_UTF_8;

    private static final String ALLOWED_METHODS_STRING =
            HttpMethod.knownMethods().stream().map(HttpMethod::name).collect(Collectors.joining(","));

    private static final String MSG_INVALID_REQUEST_PATH = HttpStatus.BAD_REQUEST + "\nInvalid request path";

    private static final HttpData DATA_INVALID_REQUEST_PATH = HttpData.ofUtf8(MSG_INVALID_REQUEST_PATH);

    private static final ChannelFutureListener CLOSE = future -> {
        final Throwable cause = future.cause();
        final Channel ch = future.channel();
        if (cause != null) {
            logException(ch, cause);
        }
        safeClose(ch);
    };

    static final ChannelFutureListener CLOSE_ON_FAILURE = future -> {
        final Throwable cause = future.cause();
        if (cause != null && !(cause instanceof ClosedPublisherException)) {
            final Channel ch = future.channel();
            logException(ch, cause);
            safeClose(ch);
        }
    };

    private static boolean warnedNullRequestId;

    private static void logException(Channel ch, Throwable cause) {
        final HttpServer server = HttpServer.get(ch);
        if (server != null) {
            Exceptions.logIfUnexpected(logger, ch, server.protocol(), cause);
        } else {
            Exceptions.logIfUnexpected(logger, ch, cause);
        }
    }

    static void safeClose(Channel ch) {
        if (!ch.isActive()) {
            return;
        }

        // Do not call Channel.close() if AbstractHttp2ConnectionHandler.close() has been invoked
        // already. Otherwise, it can trigger a bad cycle:
        //
        //   1. Channel.close() triggers AbstractHttp2ConnectionHandler.close().
        //   2. AbstractHttp2ConnectionHandler.close() triggers Http2Stream.close().
        //   3. Http2Stream.close() fails the promise of its pending writes.
        //   4. The failed promise notifies this listener (CLOSE_ON_FAILURE).
        //   5. This listener calls Channel.close().
        //   6. Repeat from step 1.
        //

        final AbstractHttp2ConnectionHandler h2handler =
                ch.pipeline().get(AbstractHttp2ConnectionHandler.class);

        if (h2handler == null || !h2handler.isClosing()) {
            ch.close();
        }
    }

    private final ServerConfig config;
    private final GracefulShutdownSupport gracefulShutdownSupport;

    private SessionProtocol protocol;

    @Nullable
    private HttpObjectEncoder responseEncoder;

    @Nullable
    private final ProxiedAddresses proxiedAddresses;

    private final IdentityHashMap<DecodedHttpRequest, HttpResponse> unfinishedRequests;
    private boolean isReading;
    private boolean handledLastRequest;

    HttpServerHandler(ServerConfig config,
                      GracefulShutdownSupport gracefulShutdownSupport,
                      @Nullable HttpObjectEncoder responseEncoder,
                      SessionProtocol protocol,
                      @Nullable ProxiedAddresses proxiedAddresses) {

        assert protocol == H1 || protocol == H1C || protocol == H2;

        this.config = requireNonNull(config, "config");
        this.gracefulShutdownSupport = requireNonNull(gracefulShutdownSupport, "gracefulShutdownSupport");

        this.protocol = requireNonNull(protocol, "protocol");
        this.responseEncoder = responseEncoder;
        this.proxiedAddresses = proxiedAddresses;

        unfinishedRequests = new IdentityHashMap<>();
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public int unfinishedRequests() {
        return unfinishedRequests.size();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Give the unfinished streaming responses a chance to close themselves before we abort them,
        // so that successful responses are not aborted due to a race condition like the following:
        //
        // 1) A publisher of a response stream sends the complete response
        //    but does not call StreamWriter.close() just yet.
        // 2) An HTTP/1 client receives the complete response and closes the connection, which is totally fine.
        // 3) The response stream is aborted once the server detects the disconnection.
        // 4) The publisher calls StreamWriter.close() but it's aborted already.
        //
        // To reduce the chance of such situation, we wait a little bit before aborting unfinished responses.

        switch (protocol) {
            case H1C:
            case H1:
                // XXX(trustin): How much time is 'a little bit'?
                ctx.channel().eventLoop().schedule(this::cleanup, 1, TimeUnit.SECONDS);
                break;
            default:
                // HTTP/2 is unaffected by this issue because a client is expected to wait for a frame with
                // endOfStream set.
                cleanup();
        }
    }

    private void cleanup() {
        if (responseEncoder != null) {
            responseEncoder.close();
        }

        unfinishedRequests.forEach((req, res) -> {
            // Mark the request stream as closed due to disconnection.
            req.close(ClosedSessionException.get());
            // XXX(trustin): Should we allow aborting with an exception other than AbortedStreamException?
            //               (ClosedSessionException in this case.)
            res.abort();
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        isReading = true; // Cleared in channelReadComplete()

        if (msg instanceof Http2Settings) {
            handleHttp2Settings(ctx, (Http2Settings) msg);
        } else {
            handleRequest(ctx, (DecodedHttpRequest) msg);
        }
    }

    private void handleHttp2Settings(ChannelHandlerContext ctx, Http2Settings h2settings) {
        if (h2settings.isEmpty()) {
            logger.trace("{} HTTP/2 settings: <empty>", ctx.channel());
        } else {
            logger.debug("{} HTTP/2 settings: {}", ctx.channel(), h2settings);
        }

        if (protocol == H1) {
            protocol = H2;
        } else if (protocol == H1C) {
            protocol = H2C;
        }

        final ChannelPipeline pipeline = ctx.pipeline();
        final Http2ConnectionHandler handler = pipeline.get(Http2ConnectionHandler.class);
        if (responseEncoder == null) {
            responseEncoder = new Http2ObjectEncoder(ctx, handler.encoder());
        } else if (responseEncoder instanceof Http1ObjectEncoder) {
            responseEncoder.close();
            responseEncoder = new Http2ObjectEncoder(ctx, handler.encoder());
        }

        // Update the connection-level flow-control window size.
        final int initialWindow = config.http2InitialConnectionWindowSize();
        if (initialWindow > DEFAULT_WINDOW_SIZE) {
            incrementLocalWindowSize(pipeline, initialWindow - DEFAULT_WINDOW_SIZE);
        }
    }

    private static void incrementLocalWindowSize(ChannelPipeline pipeline, int delta) {
        try {
            final Http2Connection connection = pipeline.get(Http2ServerConnectionHandler.class).connection();
            connection.local().flowController().incrementWindowSize(connection.connectionStream(), delta);
        } catch (Http2Exception e) {
            logger.warn("Failed to increment local flowController window size: {}", delta, e);
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, DecodedHttpRequest req) throws Exception {
        // Ignore the request received after the last request,
        // because we are going to close the connection after sending the last response.
        if (handledLastRequest) {
            return;
        }

        // If we received the message with keep-alive disabled,
        // we should not accept a request anymore.
        if (!req.isKeepAlive()) {
            handledLastRequest = true;
        }

        final RequestHeaders headers = req.headers();

        // Handle 'OPTIONS * HTTP/1.1'.
        final String originalPath = headers.path();
        if (originalPath.isEmpty() || originalPath.charAt(0) != '/') {
            if (headers.method() == HttpMethod.OPTIONS && "*".equals(originalPath)) {
                handleOptions(ctx, req);
            } else {
                rejectInvalidPath(ctx, req);
            }
            return;
        }

        // Validate and split path and query.
        final PathAndQuery pathAndQuery = PathAndQuery.parse(originalPath);
        if (pathAndQuery == null) {
            rejectInvalidPath(ctx, req);
            return;
        }

        final String hostname = hostname(headers);
        final VirtualHost host = config.findVirtualHost(hostname);

        final RoutingContext routingCtx =
                DefaultRoutingContext.of(host, hostname, pathAndQuery.path(), pathAndQuery.query(),
                                         headers, isCorsPreflightRequest(req));
        // Find the service that matches the path.
        final Routed<ServiceConfig> routed;
        try {
            routed = host.findServiceConfig(routingCtx);
        } catch (HttpStatusException cause) {
            // There's no chance that an HttpResponseException is raised so we just handle HttpStatusException.
            // Just pass the null as the cause because we don't want to log HttpStatusException as the cause.
            respond(ctx, host.accessLogWriter(), req, pathAndQuery, cause.httpStatus(), null, null);
            return;
        } catch (Throwable cause) {
            logger.warn("{} Unexpected exception: {}", ctx.channel(), req, cause);
            respond(ctx, host.accessLogWriter(), req, pathAndQuery,
                    HttpStatus.INTERNAL_SERVER_ERROR, null, cause);
            return;
        }
        if (!routed.isPresent()) {
            // No services matched the path.
            handleNonExistentMapping(ctx, host.accessLogWriter(), req, host, pathAndQuery, routingCtx);
            return;
        }

        // Decode the request and create a new invocation context from it to perform an invocation.
        final RoutingResult routingResult = routed.routingResult();
        final ServiceConfig serviceCfg = routed.value();
        final Service<HttpRequest, HttpResponse> service = serviceCfg.service();
        final Channel channel = ctx.channel();
        final InetAddress remoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress();

        final InetAddress clientAddress;
        if (config.clientAddressTrustedProxyFilter().test(remoteAddress)) {
            clientAddress = determineClientAddress(headers, config.clientAddressSources(), proxiedAddresses,
                                                   remoteAddress, config.clientAddressFilter());
        } else {
            clientAddress = remoteAddress;
        }

        final DefaultServiceRequestContext reqCtx = new DefaultServiceRequestContext(
                serviceCfg, channel, config.meterRegistry(), protocol,
                nextRequestId(), routingCtx, routingResult,
                req, getSSLSession(channel), proxiedAddresses, clientAddress);

        try (SafeCloseable ignored = reqCtx.push()) {
            final RequestLogBuilder logBuilder = reqCtx.logBuilder();
            HttpResponse serviceResponse;
            try {
                req.init(reqCtx);
                serviceResponse = service.serve(reqCtx, req);
            } catch (HttpResponseException cause) {
                serviceResponse = cause.httpResponse();
            } catch (Throwable cause) {
                final HttpStatus status;
                final Throwable newCause;
                if (cause instanceof HttpStatusException) {
                    status = ((HttpStatusException) cause).httpStatus();
                    // We don't want to log HttpStatusException and HttpResponseException as the cause.
                    newCause = null;
                } else {
                    logger.warn("{} Unexpected exception: {}, {}", reqCtx, service, req, cause);
                    status = HttpStatus.INTERNAL_SERVER_ERROR;
                    newCause = cause;
                }
                respond(ctx, reqCtx, reqCtx.accessLogWriter(), status, null, newCause);
                return;
            }
            final HttpResponse res = serviceResponse;

            final EventLoop eventLoop = channel.eventLoop();

            // Keep track of the number of unfinished requests and
            // clean up the request stream when response stream ends.
            final boolean isTransient = service.as(TransientService.class).isPresent();
            if (!isTransient) {
                gracefulShutdownSupport.inc();
            }
            unfinishedRequests.put(req, res);

            if (service.shouldCachePath(pathAndQuery.path(), pathAndQuery.query(), routed.route())) {
                reqCtx.log().addListener(log -> {
                    final HttpStatus status = log.responseHeaders().status();
                    if (status.code() >= 200 && status.code() < 400) {
                        pathAndQuery.storeInCache(originalPath);
                    }
                }, RequestLogAvailability.COMPLETE);
            }

            req.completionFuture().handle((ret, cause) -> {
                if (cause == null) {
                    logBuilder.endRequest();
                } else {
                    logBuilder.endRequest(cause);
                    // NB: logBuilder.endResponse(cause) will be called by HttpResponseSubscriber below
                }
                return null;
            }).exceptionally(CompletionActions::log);

            res.completionFuture().handleAsync((ret, cause) -> {
                req.abort();
                // NB: logBuilder.endResponse() is called by HttpResponseSubscriber below.
                if (!isTransient) {
                    gracefulShutdownSupport.dec();
                }
                unfinishedRequests.remove(req);
                if (unfinishedRequests.isEmpty() && handledLastRequest) {
                    ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(CLOSE);
                }
                return null;
            }, eventLoop).exceptionally(CompletionActions::log);

            // Set the response to the request in order to be able to immediately abort the response
            // when the peer cancels the stream.
            req.setResponse(res);

            assert responseEncoder != null;
            final HttpResponseSubscriber resSubscriber =
                    new HttpResponseSubscriber(ctx, responseEncoder, reqCtx, req,
                                               config.isServerHeaderEnabled(), config.isDateHeaderEnabled());
            reqCtx.setRequestTimeoutChangeListener(resSubscriber);
            res.subscribe(resSubscriber, eventLoop, WITH_POOLED_OBJECTS);
        }
    }

    private void handleOptions(ChannelHandlerContext ctx, DecodedHttpRequest req) {
        respond(ctx,
                newEarlyRespondingRequestContext(ctx, req, req.path(), null),
                config.accessLogWriter(), ResponseHeaders.builder(HttpStatus.OK)
                                                         .add(HttpHeaderNames.ALLOW, ALLOWED_METHODS_STRING),
                null, null);
    }

    private void rejectInvalidPath(ChannelHandlerContext ctx, DecodedHttpRequest req) {
        // Reject requests without a valid path.
        respond(ctx, config.accessLogWriter(), req, HttpStatus.BAD_REQUEST, DATA_INVALID_REQUEST_PATH,
                new ProtocolViolationException(MSG_INVALID_REQUEST_PATH));
    }

    private void handleNonExistentMapping(ChannelHandlerContext ctx, AccessLogWriter accessLogWriter,
                                          DecodedHttpRequest req,
                                          VirtualHost host, PathAndQuery pathAndQuery,
                                          RoutingContext routingCtx) {

        final String path = routingCtx.path();
        if (path.charAt(path.length() - 1) != '/') {
            // Handle the case where /path doesn't exist but /path/ exists.
            final String pathWithSlash = path + '/';
            if (host.findServiceConfig(routingCtx.overridePath(pathWithSlash)).isPresent()) {
                final String location;
                final String originalPath = req.path();
                if (path.length() == originalPath.length()) {
                    location = pathWithSlash;
                } else {
                    location = pathWithSlash + originalPath.substring(path.length());
                }
                redirect(ctx, accessLogWriter, req, pathAndQuery, location);
                return;
            }
        }

        respond(ctx, accessLogWriter, req, HttpStatus.NOT_FOUND, null, null);
    }

    private static String hostname(RequestHeaders headers) {
        final String authority = headers.authority();
        assert authority != null;
        final int hostnameColonIdx = authority.lastIndexOf(':');
        if (hostnameColonIdx < 0) {
            return authority;
        }

        return authority.substring(0, hostnameColonIdx);
    }

    private void redirect(ChannelHandlerContext ctx, AccessLogWriter accessLogWriter, DecodedHttpRequest req,
                          PathAndQuery pathAndQuery, String location) {
        respond(ctx,
                newEarlyRespondingRequestContext(ctx, req, pathAndQuery.path(), pathAndQuery.query()),
                accessLogWriter, ResponseHeaders.builder(HttpStatus.TEMPORARY_REDIRECT)
                                                .add(HttpHeaderNames.LOCATION, location),
                null, null);
    }

    // TODO(minwoox) Refactor response() methods so that they are easily read
    private void respond(ChannelHandlerContext ctx, AccessLogWriter accessLogWriter, DecodedHttpRequest req,
                         HttpStatus status, @Nullable HttpData resContent, @Nullable Throwable cause) {
        respond(ctx, newEarlyRespondingRequestContext(ctx, req, req.path(), null),
                accessLogWriter, status, resContent, cause);
    }

    private void respond(ChannelHandlerContext ctx, AccessLogWriter accessLogWriter, DecodedHttpRequest req,
                         PathAndQuery pathAndQuery, HttpStatus status, @Nullable HttpData resContent,
                         @Nullable Throwable cause) {
        respond(ctx, newEarlyRespondingRequestContext(ctx, req, pathAndQuery.path(), pathAndQuery.query()),
                accessLogWriter, status, resContent, cause);
    }

    private void respond(ChannelHandlerContext ctx, RequestContext reqCtx, AccessLogWriter accessLogWriter,
                         HttpStatus status,
                         @Nullable HttpData resContent,
                         @Nullable Throwable cause) {

        if (status.code() < 400) {
            respond(ctx, reqCtx, accessLogWriter, ResponseHeaders.builder(status), null, cause);
            return;
        }

        if (reqCtx.method() == HttpMethod.HEAD || status.isContentAlwaysEmpty()) {
            resContent = null;
        } else if (resContent == null) {
            resContent = status.toHttpData();
        } else {
            assert !resContent.isEmpty();
        }

        respond(ctx, reqCtx,
                accessLogWriter, ResponseHeaders.builder(status)
                                                .addObject(HttpHeaderNames.CONTENT_TYPE, ERROR_CONTENT_TYPE),
                resContent, cause);
    }

    private void respond(ChannelHandlerContext ctx, RequestContext reqCtx, AccessLogWriter accessLogWriter,
                         ResponseHeadersBuilder resHeaders, @Nullable HttpData resContent,
                         @Nullable Throwable cause) {
        if (!handledLastRequest) {
            respond0(reqCtx, accessLogWriter, true, resHeaders, resContent, cause)
                    .addListener(CLOSE_ON_FAILURE);
        } else {
            respond0(reqCtx, accessLogWriter, false, resHeaders, resContent, cause)
                    .addListener(CLOSE);
        }

        if (!isReading) {
            ctx.flush();
        }
    }

    private ChannelFuture respond0(
            RequestContext reqCtx, AccessLogWriter accessLogWriter, boolean addKeepAlive,
            ResponseHeadersBuilder resHeaders, @Nullable HttpData resContent, @Nullable Throwable cause) {

        assert resContent == null || !resContent.isEmpty() : resContent;

        // No need to consume further since the response is ready.
        final DecodedHttpRequest req = (DecodedHttpRequest) reqCtx.request();
        assert req != null;
        req.close();
        final RequestLogBuilder logBuilder = reqCtx.logBuilder();
        if (cause == null) {
            logBuilder.endRequest();
        } else {
            logBuilder.endRequest(cause);
        }

        final boolean hasContent = resContent != null;

        logBuilder.startResponse();
        assert responseEncoder != null;
        if (addKeepAlive) {
            addKeepAliveHeaders(resHeaders);
        }
        // Note that it is perfectly fine not to set the 'content-length' header to the last response
        // of an HTTP/1 connection. We set it anyway to work around overly strict HTTP clients that always
        // require a 'content-length' header for non-chunked responses.
        setContentLength(req, resHeaders, hasContent ? resContent.length() : 0);

        final ResponseHeaders immutableResHeaders = resHeaders.build();
        ChannelFuture future = responseEncoder.writeHeaders(
                req.id(), req.streamId(), immutableResHeaders, !hasContent);
        logBuilder.responseHeaders(immutableResHeaders);
        if (hasContent) {
            logBuilder.increaseResponseLength(resContent);
            future = responseEncoder.writeData(req.id(), req.streamId(), resContent, true);
        }

        future.addListener(f -> {
            if (cause == null && f.isSuccess()) {
                logBuilder.endResponse();
            } else {
                // Respect the first specified cause.
                logBuilder.endResponse(firstNonNull(cause, f.cause()));
            }
            reqCtx.log().addListener(accessLogWriter::log, RequestLogAvailability.COMPLETE);
        });
        return future;
    }

    /**
     * Sets the keep alive header as per:
     * - https://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
     */
    private void addKeepAliveHeaders(ResponseHeadersBuilder headers) {
        if (protocol == H1 || protocol == H1C) {
            headers.set(HttpHeaderNames.CONNECTION, "keep-alive");
        } else {
            // Do not add the 'connection' header for HTTP/2 responses.
            // See https://tools.ietf.org/html/rfc7540#section-8.1.2.2
        }
    }

    /**
     * Sets the 'content-length' header to the response.
     */
    private static void setContentLength(HttpRequest req, ResponseHeadersBuilder headers,
                                         int contentLength) {
        // https://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.4
        // prohibits to send message body for below cases.
        // and in those cases, content should be empty.
        if (req.method() == HttpMethod.HEAD || headers.status().isContentAlwaysEmpty()) {
            return;
        }
        headers.setInt(HttpHeaderNames.CONTENT_LENGTH, contentLength);
    }

    @Nullable
    private static SSLSession getSSLSession(Channel channel) {
        final SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        return sslHandler != null ? sslHandler.engine().getSession() : null;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        isReading = false;
        ctx.flush();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent ||
            evt instanceof SslCloseCompletionEvent ||
            evt instanceof ChannelInputShutdownReadComplete) {
            // Expected events
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

    private EarlyRespondingRequestContext newEarlyRespondingRequestContext(ChannelHandlerContext ctx,
                                                                           DecodedHttpRequest req,
                                                                           String path,
                                                                           @Nullable String query) {
        final Channel channel = ctx.channel();
        final EarlyRespondingRequestContext reqCtx =
                new EarlyRespondingRequestContext(channel, NoopMeterRegistry.get(), protocol(),
                                                  nextRequestId(), req.method(),
                                                  path, query, req);

        final RequestLogBuilder logBuilder = reqCtx.logBuilder();
        logBuilder.startRequest(channel, protocol());
        logBuilder.requestHeaders(req.headers());

        return reqCtx;
    }

    private RequestId nextRequestId() {
        final RequestId id = config.requestIdGenerator().get();
        if (id == null) {
            if (!warnedNullRequestId) {
                warnedNullRequestId = true;
                logger.warn("requestIdGenerator.get() returned null; using RequestId.random()");
            }
            return RequestId.random();
        } else {
            return id;
        }
    }

    private static final class EarlyRespondingRequestContext extends NonWrappingRequestContext {

        private final Channel channel;
        private final DefaultRequestLog requestLog;

        EarlyRespondingRequestContext(Channel channel, MeterRegistry meterRegistry,
                                      SessionProtocol sessionProtocol, RequestId id, HttpMethod method,
                                      String path, @Nullable String query, HttpRequest request) {
            super(meterRegistry, sessionProtocol, id, method, path, query, request, null);
            this.channel = requireNonNull(channel, "channel");
            requestLog = new DefaultRequestLog(this);
        }

        @Override
        public RequestContext newDerivedContext(RequestId id,
                                                @Nullable HttpRequest req,
                                                @Nullable RpcRequest rpcReq) {
            // There are no attributes which should be copied to a new instance.
            requireNonNull(req, "req");
            return new EarlyRespondingRequestContext(channel, meterRegistry(), sessionProtocol(),
                                                     id, method(), path(), query(), req);
        }

        @Override
        protected Channel channel() {
            return channel;
        }

        @Nullable
        @Override
        public SSLSession sslSession() {
            return ChannelUtil.findSslSession(channel);
        }

        @Override
        public RequestLog log() {
            return requestLog;
        }

        @Override
        public RequestLogBuilder logBuilder() {
            return requestLog;
        }

        @Override
        public EventLoop eventLoop() {
            return channel.eventLoop();
        }
    }
}
