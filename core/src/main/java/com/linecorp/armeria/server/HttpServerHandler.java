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
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.CLOSE_STRING;
import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.common.RequestTargetCache;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

final class HttpServerHandler extends ChannelInboundHandlerAdapter implements HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);

    private static final String ALLOWED_METHODS_STRING =
            HttpMethod.knownMethods().stream().map(HttpMethod::name).collect(Collectors.joining(","));

    private static final InetSocketAddress UNKNOWN_ADDR;

    static {
        InetAddress unknownAddr;
        try {
            unknownAddr = InetAddress.getByAddress("<unknown>", new byte[] { 0, 0, 0, 0 });
        } catch (Exception e1) {
            // Just in case a certain JRE implementation doesn't accept the hostname '<unknown>'
            try {
                unknownAddr = InetAddress.getByAddress(new byte[] { 0, 0, 0, 0 });
            } catch (Exception e2) {
                // Should never reach here.
                final Error err = new Error(e2);
                err.addSuppressed(e1);
                throw err;
            }
        }
        UNKNOWN_ADDR = new InetSocketAddress(unknownAddr, 1);
    }

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
        if (cause == null) {
            return;
        }
        if (cause instanceof ClosedSessionException) {
            safeClose(future.channel());
            return;
        }
        if (cause instanceof ClosedStreamException) {
            return;
        }

        final Channel ch = future.channel();
        logException(ch, cause);
        safeClose(ch);
    };

    private static boolean warnedRequestIdGenerateFailure;

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
    private SSLSession sslSession;

    @Nullable
    private ServerHttpObjectEncoder responseEncoder;

    @Nullable
    private final ProxiedAddresses proxiedAddresses;
    @Nullable
    private InetSocketAddress remoteAddress;
    @Nullable
    private InetSocketAddress localAddress;

    private final IdentityHashMap<DecodedHttpRequest, HttpResponse> unfinishedRequests;
    private boolean isReading;
    private boolean isCleaning;
    private boolean handledLastRequest;

    HttpServerHandler(ServerConfig config,
                      GracefulShutdownSupport gracefulShutdownSupport,
                      @Nullable ServerHttpObjectEncoder responseEncoder,
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
        if (responseEncoder != null) {
            // Immediately close responseEncoder so that a late response is completed with
            // a ClosedSessionException.
            responseEncoder.close();
        }

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
        if (!unfinishedRequests.isEmpty()) {
            isCleaning = true;
            final ClosedSessionException cause = ClosedSessionException.get();
            unfinishedRequests.forEach((req, res) -> {
                // An HTTP2 request is cancelled by Http2RequestDecoder.onRstStreamRead()
                final boolean cancel = !protocol.isMultiplex();
                // Mark the request stream as closed due to disconnection.
                req.abortResponse(cause, cancel);
            });
            unfinishedRequests.clear();
        }
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
        final ChannelHandlerContext connectionHandlerCtx =
                pipeline.context(Http2ServerConnectionHandler.class);
        final Http2ServerConnectionHandler connectionHandler =
                (Http2ServerConnectionHandler) connectionHandlerCtx.handler();
        if (responseEncoder instanceof Http1ObjectEncoder) {
            responseEncoder.close();
        }
        responseEncoder = connectionHandler.getOrCreateResponseEncoder(connectionHandlerCtx);

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
        final ServerHttpObjectEncoder responseEncoder = this.responseEncoder;
        assert responseEncoder != null;

        // Ignore the request received after the last request,
        // because we are going to close the connection after sending the last response.
        if (handledLastRequest) {
            req.abort();
            decreasePendingRequests();
            return;
        }

        // If we received the message with keep-alive disabled,
        // we should not accept a request anymore.
        if (!req.isKeepAlive()) {
            handledLastRequest = true;
            responseEncoder.keepAliveHandler().disconnectWhenFinished();
        }

        final Channel channel = ctx.channel();
        final RequestHeaders headers = req.headers();
        final InetSocketAddress remoteAddress = firstNonNull(remoteAddress(channel), UNKNOWN_ADDR);
        final InetSocketAddress localAddress = firstNonNull(localAddress(channel), UNKNOWN_ADDR);
        final ProxiedAddresses proxiedAddresses = determineProxiedAddresses(remoteAddress, headers);
        final InetAddress clientAddress = config.clientAddressMapper().apply(proxiedAddresses).getAddress();
        final EventLoop channelEventLoop = channel.eventLoop();

        final RoutingContext routingCtx = req.routingContext();
        final RoutingStatus routingStatus = routingCtx.status();
        if (!routingStatus.routeMustExist()) {
            final ServiceRequestContext reqCtx = newEarlyRespondingRequestContext(
                    channel, req, proxiedAddresses, clientAddress, remoteAddress, localAddress, routingCtx,
                    channelEventLoop);

            // Handle 'OPTIONS * HTTP/1.1'.
            if (routingStatus == RoutingStatus.OPTIONS) {
                handleOptions(ctx, reqCtx);
                decreasePendingRequests();
                return;
            }

            throw new Error(); // Should never reach here.
        }

        // Find the service that matches the path.
        final Routed<ServiceConfig> routed = req.route();
        assert routed != null;

        // Decode the request and create a new invocation context from it to perform an invocation.
        final RoutingResult routingResult = routed.routingResult();
        final ServiceConfig serviceCfg = routed.value();
        final HttpService service = serviceCfg.service();
        final EventLoop serviceEventLoop;
        final EventLoopGroup serviceWorkerGroup = serviceCfg.serviceWorkerGroup();
        if (serviceWorkerGroup == config.workerGroup()) {
            serviceEventLoop = channelEventLoop;
        } else {
            serviceEventLoop = serviceWorkerGroup.next();
        }
        final DefaultServiceRequestContext reqCtx = new DefaultServiceRequestContext(
                serviceCfg, channel, serviceEventLoop, config.meterRegistry(), protocol,
                nextRequestId(routingCtx, serviceCfg), routingCtx, routingResult, req.exchangeType(),
                req, sslSession, proxiedAddresses, clientAddress, remoteAddress, localAddress,
                req.requestStartTimeNanos(), req.requestStartTimeMicros(), serviceCfg.contextHook());

        HttpResponse res;
        req.init(reqCtx);
        final CompletableFuture<Void> whenAggregated = req.whenAggregated();
        if (whenAggregated != null) {
            res = HttpResponse.of(whenAggregated.thenApply(ignored -> {
                if (serviceEventLoop.inEventLoop()) {
                    return serve0(req, service, reqCtx, req.isHttp1WebSocket());
                }
                return serveInServiceEventLoop(req, service, reqCtx, serviceEventLoop, req.isHttp1WebSocket());
            }));
        } else {
            if (serviceEventLoop.inEventLoop()) {
                res = serve0(req, service, reqCtx, req.isHttp1WebSocket());
            } else {
                res = serveInServiceEventLoop(req, service, reqCtx, serviceEventLoop, req.isHttp1WebSocket());
            }
        }
        res = res.recover(cause -> {
            reqCtx.logBuilder().responseCause(cause);
            // Recover the failed response with the error handler.
            try (SafeCloseable ignored = reqCtx.push()) {
                return serviceCfg.errorHandler().onServiceException(reqCtx, cause);
            }
        });

        // Keep track of the number of unfinished requests and
        // clean up the request stream when response stream ends.
        final boolean isTransientService =
                serviceCfg.service().as(TransientService.class) != null;
        if (!isTransientService) {
            gracefulShutdownSupport.inc();
        }
        unfinishedRequests.put(req, res);

        if (service.shouldCachePath(routingCtx.path(), routingCtx.query(), routed.route())) {
            reqCtx.log().whenComplete().thenAccept(log -> {
                final int statusCode = log.responseHeaders().status().code();
                if (statusCode >= 200 && statusCode < 400) {
                    RequestTargetCache.putForServer(req.path(), routingCtx.requestTarget());
                }
            });
        }

        final RequestAndResponseCompleteHandler handler =
                new RequestAndResponseCompleteHandler(channelEventLoop, ctx, reqCtx, req,
                                                      isTransientService);
        req.whenComplete().handle(handler.requestCompleteHandler);

        // A future which is completed when the all response objects are written to channel and
        // the returned promises are done.
        final CompletableFuture<Void> resWriteFuture = new CompletableFuture<>();
        resWriteFuture.handle(handler.responseCompleteHandler);

        // Set the response to the request in order to be able to immediately abort the response
        // when the peer cancels the stream.
        req.setResponse(res);

        if (req.isHttp1WebSocket()) {
            assert responseEncoder instanceof Http1ObjectEncoder;
            final WebSocketHttp1ResponseSubscriber resSubscriber =
                    new WebSocketHttp1ResponseSubscriber(ctx, responseEncoder, reqCtx, req, resWriteFuture);
            res.subscribe(resSubscriber, channelEventLoop, SubscriptionOption.WITH_POOLED_OBJECTS);
        } else if (reqCtx.exchangeType().isResponseStreaming()) {
            final AbstractHttpResponseSubscriber resSubscriber =
                    new HttpResponseSubscriber(ctx, responseEncoder, reqCtx, req, resWriteFuture);
            res.subscribe(resSubscriber, channelEventLoop, SubscriptionOption.WITH_POOLED_OBJECTS);
        } else {
            final AggregatedHttpResponseHandler resHandler =
                    new AggregatedHttpResponseHandler(ctx, responseEncoder, reqCtx, req, resWriteFuture);
            res.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), channelEventLoop))
               .handle(resHandler);
        }
    }

    private void decreasePendingRequests() {
        if (protocol.isExplicitHttp1()) {
            config.serverMetrics().decreasePendingHttp1Requests();
        } else {
            assert protocol.isExplicitHttp2();
            config.serverMetrics().decreasePendingHttp2Requests();
        }
    }

    private void increaseActiveRequests(boolean isHttp1WebSocket) {
        if (isHttp1WebSocket) {
            config.serverMetrics().increaseActiveHttp1WebSocketRequests();
        } else if (protocol.isExplicitHttp1()) {
            config.serverMetrics().increaseActiveHttp1Requests();
        } else {
            assert protocol.isExplicitHttp2();
            config.serverMetrics().increaseActiveHttp2Requests();
        }
    }

    private HttpResponse serve0(HttpRequest req, HttpService service, DefaultServiceRequestContext reqCtx,
                                boolean isHttp1WebSocket) {
        try (SafeCloseable ignored = reqCtx.push()) {
            try {
                decreasePendingRequests();
                increaseActiveRequests(isHttp1WebSocket);
                return service.serve(reqCtx, req);
            } catch (Throwable cause) {
                // No need to consume further since the response is ready.
                if (cause instanceof HttpResponseException || cause instanceof HttpStatusException) {
                    req.abort(ResponseCompleteException.get());
                } else {
                    req.abort(cause);
                }
                return HttpResponse.ofFailure(cause);
            }
        }
    }

    private HttpResponse serveInServiceEventLoop(DecodedHttpRequest req,
                                                 HttpService service,
                                                 DefaultServiceRequestContext reqCtx,
                                                 EventLoop serviceEventLoop,
                                                 boolean isHttp1WebSocket) {
        return HttpResponse.of(() -> serve0(req.subscribeOn(serviceEventLoop), service,
                                            reqCtx, isHttp1WebSocket),
                               serviceEventLoop)
                           .subscribeOn(serviceEventLoop);
    }

    private ProxiedAddresses determineProxiedAddresses(InetSocketAddress remoteAddress,
                                                       RequestHeaders headers) {
        if (config.clientAddressTrustedProxyFilter().test(remoteAddress.getAddress())) {
            return HttpHeaderUtil.determineProxiedAddresses(
                    headers, config.clientAddressSources(), proxiedAddresses,
                    remoteAddress, config.clientAddressFilter());
        } else {
            return proxiedAddresses != null ? proxiedAddresses : ProxiedAddresses.of(remoteAddress);
        }
    }

    @Nullable
    private InetSocketAddress remoteAddress(Channel ch) {
        final InetSocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress != null) {
            return remoteAddress;
        }

        final InetSocketAddress newRemoteAddress = ChannelUtil.remoteAddress(ch);
        this.remoteAddress = newRemoteAddress;
        return newRemoteAddress;
    }

    @Nullable
    private InetSocketAddress localAddress(Channel ch) {
        final InetSocketAddress localAddress = this.localAddress;
        if (localAddress != null) {
            return localAddress;
        }

        final InetSocketAddress newLocalAddress = ChannelUtil.localAddress(ch);
        this.localAddress = newLocalAddress;
        return newLocalAddress;
    }

    private void handleOptions(ChannelHandlerContext ctx, ServiceRequestContext reqCtx) {
        respond(ctx, reqCtx,
                ResponseHeaders.builder(HttpStatus.OK)
                               .add(HttpHeaderNames.ALLOW, ALLOWED_METHODS_STRING),
                HttpData.empty(), null);
    }

    private void respond(ChannelHandlerContext ctx, ServiceRequestContext reqCtx,
                         ResponseHeadersBuilder resHeaders, HttpData resContent,
                         @Nullable Throwable cause) {
        if (!handledLastRequest) {
            respond(reqCtx, resHeaders, resContent, cause).addListener(CLOSE_ON_FAILURE);
        } else {
            respond(reqCtx, resHeaders, resContent, cause).addListener(CLOSE);
        }

        if (!isReading) {
            ctx.flush();
        }
    }

    private ChannelFuture respond(ServiceRequestContext reqCtx, ResponseHeadersBuilder resHeaders,
                                  HttpData resContent, @Nullable Throwable cause) {
        // No need to consume further since the response is ready.
        final DecodedHttpRequest req = (DecodedHttpRequest) reqCtx.request();
        if (req instanceof HttpRequestWriter) {
            ((HttpRequestWriter) req).close();
        }
        final RequestLogBuilder logBuilder = reqCtx.logBuilder();
        if (cause == null) {
            logBuilder.endRequest();
        } else {
            logBuilder.endRequest(cause);
        }

        final boolean hasContent = !resContent.isEmpty();

        logBuilder.startResponse();
        assert responseEncoder != null;
        if (handledLastRequest) {
            addConnectionCloseHeaders(resHeaders);
        }

        // Note that it is perfectly fine not to set the 'content-length' header to the last response
        // of an HTTP/1 connection. We set it anyway to work around overly strict HTTP clients that always
        // require a 'content-length' header for non-chunked responses.
        setContentLength(req, resHeaders, hasContent ? resContent.length() : 0);

        final ResponseHeaders immutableResHeaders = resHeaders.build();
        ChannelFuture future = responseEncoder.writeHeaders(
                req.id(), req.streamId(), immutableResHeaders, !hasContent, reqCtx.method());
        logBuilder.responseHeaders(immutableResHeaders);
        if (hasContent) {
            logBuilder.increaseResponseLength(resContent);
            future = responseEncoder.writeData(req.id(), req.streamId(), resContent, true);
        }

        future.addListener(f -> {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                if (cause == null && f.isSuccess()) {
                    logBuilder.endResponse();
                } else {
                    // Respect the first specified cause.
                    logBuilder.endResponse(firstNonNull(cause, f.cause()));
                }
            }
            reqCtx.log().whenComplete().thenAccept(log -> {
                try (SafeCloseable ignored = reqCtx.push()) {
                    reqCtx.config().accessLogWriter().log(log);
                }
            });
        });
        return future;
    }

    private void addConnectionCloseHeaders(ResponseHeadersBuilder headers) {
        if (protocol == H1 || protocol == H1C) {
            headers.set(HttpHeaderNames.CONNECTION, CLOSE_STRING);
        } else {
            // Do not add the 'connection' header for HTTP/2 responses.
            // See https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.2
        }
    }

    /**
     * Sets the 'content-length' header to the response.
     */
    private static void setContentLength(HttpRequest req, ResponseHeadersBuilder headers,
                                         int contentLength) {
        // https://datatracker.ietf.org/doc/html/rfc2616#section-4.4
        // prohibits to send message body for below cases.
        // and in those cases, content should be empty.
        if (req.method() == HttpMethod.HEAD || headers.status().isContentAlwaysEmpty()) {
            return;
        }
        headers.contentLength(contentLength);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        isReading = false;
        ctx.flush();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent) {
            final SslHandler sslHandler = ctx.channel().pipeline().get(SslHandler.class);
            sslSession = sslHandler != null ? sslHandler.engine().getSession() : null;
            return;
        }

        if (evt instanceof SslCloseCompletionEvent ||
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

    private ServiceRequestContext newEarlyRespondingRequestContext(Channel channel, DecodedHttpRequest req,
                                                                   ProxiedAddresses proxiedAddresses,
                                                                   InetAddress clientAddress,
                                                                   InetSocketAddress remoteAddress,
                                                                   InetSocketAddress localAddress,
                                                                   RoutingContext routingCtx,
                                                                   EventLoop eventLoop) {
        final ServiceConfig serviceConfig = routingCtx.virtualHost().fallbackServiceConfig();
        final RoutingResult routingResult = RoutingResult.builder()
                                                         .path(routingCtx.path())
                                                         .build();
        return new DefaultServiceRequestContext(
                serviceConfig,
                channel, eventLoop, NoopMeterRegistry.get(), protocol(),
                nextRequestId(routingCtx, serviceConfig), routingCtx, routingResult, req.exchangeType(),
                req, sslSession, proxiedAddresses, clientAddress, remoteAddress, localAddress,
                System.nanoTime(), SystemInfo.currentTimeMicros(), NOOP_CONTEXT_HOOK);
    }

    private static RequestId nextRequestId(RoutingContext routingCtx, ServiceConfig serviceConfig) {
        try {
            final RequestId id = serviceConfig.requestIdGenerator().apply(routingCtx);
            if (id != null) {
                return id;
            }

            if (!warnedNullRequestId) {
                warnedNullRequestId = true;
                logger.warn("requestIdGenerator.apply(routingCtx) returned null; using RequestId.random()");
            }
            return RequestId.random();
        } catch (Exception e) {
            if (!warnedRequestIdGenerateFailure) {
                warnedRequestIdGenerateFailure = true;
                logger.warn("requestIdGenerator.apply(routingCtx) threw an exception; using RequestId.random()",
                            e);
            }
            return RequestId.random();
        }
    }

    private final class RequestAndResponseCompleteHandler {

        final BiFunction<Void, @Nullable Throwable, Void> requestCompleteHandler;
        final BiFunction<Void, @Nullable Throwable, Void> responseCompleteHandler;
        private boolean requestOrResponseComplete;

        private final ChannelHandlerContext ctx;
        private final DecodedHttpRequest req;
        private final boolean isTransientService;

        RequestAndResponseCompleteHandler(EventLoop eventLoop, ChannelHandlerContext ctx,
                                          ServiceRequestContext reqCtx, DecodedHttpRequest req,
                                          boolean isTransientService) {
            this.ctx = ctx;
            this.req = req;
            this.isTransientService = isTransientService;

            assert responseEncoder != null;

            requestCompleteHandler = (unused, cause) -> {
                if (eventLoop.inEventLoop()) {
                    handleRequestComplete(reqCtx.logBuilder(), cause);
                } else {
                    eventLoop.execute(() -> handleRequestComplete(reqCtx.logBuilder(), cause));
                }
                return null;
            };

            responseCompleteHandler = (unused, cause) -> {
                assert eventLoop.inEventLoop();
                final long requestAutoAbortDelayMillis = reqCtx.requestAutoAbortDelayMillis();
                if (cause != null || !req.isOpen() || requestAutoAbortDelayMillis == 0) {
                    handleResponseComplete(cause);
                    return null;
                }
                if (requestAutoAbortDelayMillis > 0 && requestAutoAbortDelayMillis < Long.MAX_VALUE) {
                    eventLoop.schedule(() -> handleResponseComplete(null),
                                       requestAutoAbortDelayMillis, TimeUnit.MILLISECONDS);
                    return null;
                }
                // Auto aborting request is disabled.
                handleRequestOrResponseComplete();
                return null;
            };
        }

        private void handleRequestComplete(RequestLogBuilder logBuilder, @Nullable Throwable cause) {
            try {
                if (cause == null) {
                    logBuilder.endRequest();
                } else {
                    logBuilder.endRequest(cause);
                    // NB: logBuilder.endResponse(cause) will be called by HttpResponseSubscriber below.
                }
            } catch (Throwable t) {
                logger.warn("Unexpected exception:", t);
            }
            handleRequestOrResponseComplete();
        }

        private void handleResponseComplete(@Nullable Throwable cause) {
            if (cause == null || !req.isOpen()) {
                req.abort(ResponseCompleteException.get());
            } else {
                req.abort(cause);
            }
            handleRequestOrResponseComplete();
        }

        private void handleRequestOrResponseComplete() {
            try {
                if (!requestOrResponseComplete) {
                    // This will make this method is called only once after
                    // both request and response are complete.
                    requestOrResponseComplete = true;
                    return;
                }
                if (req.isHttp1WebSocket()) {
                    config.serverMetrics().decreaseActiveHttp1WebSocketRequests();
                } else if (protocol.isExplicitHttp1()) {
                    config.serverMetrics().decreaseActiveHttp1Requests();
                } else if (protocol.isExplicitHttp2()) {
                    config.serverMetrics().decreaseActiveHttp2Requests();
                }

                // NB: logBuilder.endResponse() is called by HttpResponseSubscriber.
                if (!isTransientService) {
                    gracefulShutdownSupport.dec();
                }

                // This callback could be called by `req.abortResponse(cause, cancel)` in `cleanup()`.
                // As `unfinishedRequests` is being iterated, `unfinishedRequests` should not be removed.
                if (!isCleaning) {
                    unfinishedRequests.remove(req);
                }

                final boolean needsDisconnection =
                        ctx.channel().isActive() &&
                        (handledLastRequest || responseEncoder.keepAliveHandler().needsDisconnection());
                if (needsDisconnection) {
                    // Graceful shutdown mode: If a connection needs to be closed by `KeepAliveHandler`
                    // such as a max connection age or `ServiceRequestContext.initiateConnectionShutdown()`,
                    // new requests will be ignored and the connection is closed after completing all
                    // unfinished requests.
                    if (protocol.isMultiplex()) {
                        // Initiates channel close, connection will be closed after all streams are closed.
                        ctx.channel().close();
                    } else {
                        // Stop receiving new requests.
                        handledLastRequest = true;
                        if (unfinishedRequests.isEmpty()) {
                            final long closeDelay = Flags.defaultHttp1ConnectionCloseDelayMillis();
                            if (closeDelay == 0) {
                                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(CLOSE);
                            } else {
                                ctx.channel().eventLoop().schedule(() -> {
                                    if (ctx.channel().isActive()) {
                                        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(CLOSE);
                                    }
                                }, closeDelay, TimeUnit.MILLISECONDS);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("Unexpected exception:", t);
            }
        }
    }
}
